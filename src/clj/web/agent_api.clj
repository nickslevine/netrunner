(ns web.agent-api
  "HTTP endpoints that let a local program (e.g. a coding agent playing a game,
  or an AI tutor spectating one) join, watch, and play games without holding a
  websocket connection.

  Authentication reuses the existing api-keys mechanism (see web.api-keys):
  create a key on the account the agent should play as, and pass it in the
  `X-JNet-API` header of every request. Endpoints that touch a specific game
  require the lobby to have API access enabled (the 'Allow API access' lobby
  option), matching the behaviour of web.game-api.

  The uid used for lobby membership is the account's username, which is the
  same uid a logged-in browser session gets. This means websocket sends to the
  agent 'connection' either no-op (agent not connected via browser) or land in
  a browser tab logged into the same account (handy for watching the agent
  play). The agent itself reads state by polling GET /agent-api/state."
  (:require
   [clojure.string :as str]
   [game.core.board :refer [get-all-cards]]
   [game.core.diffs :as diffs]
   [game.core.finding :refer [find-cid]]
   [game.core.process-actions :refer [commands]]
   [game.core.say :refer [make-message make-system-message]]
   [game.main :as main]
   [jinteki.utils :refer [side-from-str]]
   [monger.collection :as mc]
   [web.app-state :as app-state]
   [web.decks :as decks]
   [web.game :as game]
   [web.lobby :as lobby]
   [web.user :refer [active-user? user-keys]]
   [web.utils :refer [response]]))

(def ^:private thread-timeout-ms 10000)
(def ^:private max-wait-ms 25000)

;;; Authentication

(defn- api-key->username
  [db headers]
  (when-let [api-key (get headers "x-jnet-api")]
    (when-let [api-uuid (try (java.util.UUID/fromString api-key)
                             (catch Exception _ nil))]
      (:username (mc/find-one-as-map db "api-keys" {:api-key api-uuid} ["username"])))))

(defn- fetch-user [db username]
  (some-> (mc/find-one-as-map db "users" {:username username})
          (active-user?)
          (select-keys user-keys)))

(defn- wrap-agent
  "Authenticates the request via the X-JNet-API header and calls
  (action uid user ctx). The uid is the account username, mirroring the
  session uid a browser login would get."
  [{db :system/db headers :headers :as ctx} action]
  (if-let [username (api-key->username db headers)]
    (if-let [user (fetch-user db username)]
      (let [uid (:username user)]
        (app-state/register-user! uid user)
        (action uid user ctx))
      (response 401 {:message "User for API key not found or banned"}))
    (response 401 {:message "Missing or unknown X-JNet-API key"})))

;;; Helpers

(defmacro ^:private on-lobby-thread
  "Runs body on the lobby thread pool and waits for the result."
  [& body]
  `(deref (lobby/lobby-thread ~@body) thread-timeout-ms ::timeout))

(defmacro ^:private on-game-thread
  "Runs body on the given lobby's game thread and waits for the result."
  [lobby & body]
  `(deref (lobby/game-thread ~lobby ~@body) thread-timeout-ms ::timeout))

(defn- parse-gameid [s]
  (try (java.util.UUID/fromString (str s)) (catch Exception _ nil)))

(defn- api-access-allowed? [lobby]
  (boolean
    (or (:api-access lobby)
        (when-let [state (:state lobby)]
          (:api-access (:options @state))))))

(defn- player-brief [p]
  {:username (get-in p [:user :username])
   :side (:side p)
   :deck-selected (some? (:deck p))})

(defn- lobby-brief [{:keys [gameid title format room started players password precon
                            allow-spectator] :as lobby}]
  {:gameid (str gameid)
   :title title
   :format format
   :room room
   :started (boolean started)
   :password-protected (boolean password)
   :api-access (api-access-allowed? lobby)
   :precon precon
   :allow-spectator (boolean allow-spectator)
   :players (mapv player-brief players)})

(defn- find-game-lobby
  "The started game (with state) the uid is a player or spectator in, if any."
  [uid]
  (let [lobby (app-state/uid->lobby uid)]
    (when (and lobby (:started lobby) (:state lobby))
      lobby)))

(defn- perspective
  "Returns [description state-key] describing which of the public states the
  uid is entitled to see."
  [uid lobby]
  (if-let [player (lobby/player? uid lobby)]
    (case (:side player)
      "Corp" ["Corp" :corp-state]
      "Runner" ["Runner" :runner-state]
      nil)
    (cond
      (some #(= uid (:uid %)) (:corp-spectators lobby)) ["Corp spectator" :corp-spect-state]
      (some #(= uid (:uid %)) (:runner-spectators lobby)) ["Runner spectator" :runner-spect-state]
      (lobby/spectator? uid lobby) ["Spectator" :spect-state]
      :else nil)))

(defn- state-version
  "A cheap cursor that changes whenever either side acts or the log grows.
  Used by clients to long-poll for 'something happened'."
  [state]
  (str (get-in @state [:corp :aid] 0) "."
       (get-in @state [:runner :aid] 0) "."
       (count (:log @state)) "."
       (if (:winner @state) 1 0)))

;;; Status summaries

(defn- choice-value-str [choice]
  (let [v (:value choice)]
    (cond
      (map? v) (or (:title v) (str v))
      (keyword? v) (name v)
      :else (str v))))

(defn- prompt-brief [prompt-state]
  (when prompt-state
    (cond-> {:msg (:msg prompt-state)
             :prompt-type (:prompt-type prompt-state)}
      (:card prompt-state)
      (assoc :card (select-keys (:card prompt-state) [:cid :title]))
      (sequential? (:choices prompt-state))
      (assoc :choices (vec (map-indexed
                             (fn [i c] {:idx i
                                        :uuid (str (:uuid c))
                                        :value (choice-value-str c)})
                             (:choices prompt-state))))
      (not (sequential? (:choices prompt-state)))
      (assoc :choices (:choices prompt-state))
      (:selectable prompt-state)
      (assoc :selectable (mapv #(if (map? %) (select-keys % [:cid :title]) %)
                               (:selectable prompt-state)))
      (:base prompt-state)
      (assoc :trace (select-keys prompt-state [:base :bonus :strength :link :player
                                               :corp-credits :runner-credits])))))

(defn- side-brief [s]
  {:identity (get-in s [:identity :title])
   :credit (:credit s)
   :click (:click s)
   :hand-count (count (:hand s))
   :agenda-point (:agenda-point s)
   :tags (get-in s [:tag :base])
   :bad-publicity (get-in s [:bad-publicity :base])})

(defn- status-body [lobby]
  (let [state (:state lobby)]
    (fn [uid]
      (let [[persp state-key] (perspective uid lobby)
            view (get (diffs/public-states state) state-key)
            me-side (case persp "Corp" :corp "Runner" :runner nil)]
        (cond-> {:gameid (str (:gameid lobby))
                 :perspective persp
                 :version (state-version state)
                 :turn (:turn view)
                 :active-player (:active-player view)
                 :corp-phase-12 (boolean (:corp-phase-12 view))
                 :runner-phase-12 (boolean (:runner-phase-12 view))
                 :end-turn (boolean (:end-turn view))
                 :winner (:winner view)
                 :reason (:reason view)
                 :corp (side-brief (:corp view))
                 :runner (side-brief (:runner view))
                 :run (some-> (:run view) (select-keys [:server :position :phase :no-action]))
                 :encounter (some-> (:encounters view)
                                    (update :ice select-keys [:cid :title :strength :subroutines])
                                    (select-keys [:ice :encounter-count :no-action]))
                 :log (into [] (take-last 15 (keep :text (:log view))))}
          me-side (assoc :prompt (prompt-brief (get-in view [me-side :prompt-state]))
                         :your-turn (= (:active-player view) me-side)))))))

;;; Command argument hydration

(defn- zone-priority
  "Lower is preferred when disambiguating a title reference. Copies in hand
  are interchangeable; installed copies may differ (counters, position) so
  they stay ambiguous when there are several."
  [card]
  (case (first (:zone card))
    :hand 0
    (:servers :rig :onhost) 1
    (:play-area :current :scored :set-aside) 2
    :discard 3
    4))

(defn- resolve-card-ref
  "Turns a card reference from JSON (a cid string, a card title, or a map with
  :cid) into the actual current card map from the game state. Title lookup is
  restricted to the caller's own side's cards so that error messages cannot
  leak hidden information; cids always work since they were necessarily read
  from the caller's own view of the state."
  [state side-str card-ref]
  (let [all (get-all-cards state)]
    (cond
      (and (map? card-ref) (:cid card-ref))
      (if-let [c (find-cid (:cid card-ref) all)]
        {:card c}
        {:error (str "No card with cid " (:cid card-ref))})

      (string? card-ref)
      (if-let [c (find-cid card-ref all)]
        {:card c}
        (let [mine (filter #(= side-str (:side %)) all)
              matches (filterv #(= (str/lower-case (or (:title %) ""))
                                   (str/lower-case card-ref))
                               mine)]
          (if (empty? matches)
            {:error (str "No card of yours matching \"" card-ref "\"")}
            (let [best-pri (apply min (map zone-priority matches))
                  group (filterv #(= best-pri (zone-priority %)) matches)]
              (if (or (= 1 (count group)) (zero? best-pri))
                {:card (first group)}
                {:error (str "Ambiguous title \"" card-ref "\" — use a cid instead: "
                             (str/join ", " (map :cid group)))})))))

      :else {:error "Card reference must be a cid string, a title, or a map with a cid key"})))

(def ^:private uuid-re
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn- normalize-choice [c]
  (cond
    (map? c) c
    (number? c) c
    (and (string? c) (re-matches uuid-re c)) {:uuid c}
    :else c))

(defn- hydrate-args
  "Fixes up JSON-shaped args so the engine accepts them: resolves card
  references, keywordizes :key, and wraps uuid strings passed as :choice."
  [state side-str args]
  (let [args (or args {})]
    (if (contains? args :card)
      (let [{:keys [card error]} (resolve-card-ref state side-str (:card args))]
        (if error
          {:error error}
          {:args (cond-> (assoc args :card card)
                   (contains? args :key) (update :key keyword)
                   (contains? args :choice) (update :choice normalize-choice))}))
      {:args (cond-> args
               (contains? args :key) (update :key keyword)
               (contains? args :choice) (update :choice normalize-choice))})))

;;; Lobby endpoints

(defn lobbies-handler [ctx]
  (wrap-agent ctx
    (fn [_uid _user _ctx]
      (response 200 {:lobbies (mapv lobby-brief (app-state/get-lobbies))}))))

(defn lobby-handler
  "The lobby the caller is currently in, if any."
  [ctx]
  (wrap-agent ctx
    (fn [uid _user _ctx]
      (if-let [lobby (app-state/uid->lobby uid)]
        (let [player (lobby/player? uid lobby)]
          (response 200 (assoc (lobby-brief lobby)
                               :you (if player (:side player) "Spectator"))))
        (response 404 {:message "Not in a lobby"})))))

(defn create-lobby-handler [{body :body :as ctx}]
  (wrap-agent ctx
    (fn [uid user _ctx]
      (if (app-state/uid-in-lobby-as-player? uid)
        (response 409 {:message "Already in a lobby; leave it first"})
        (let [options {:title (or (:title body) (str (:username user) "'s game"))
                       :format (or (:format body) "standard")
                       :room "casual"
                       :side (or (:side body) "Any Side")
                       :password (:password body)
                       :precon (:precon body)
                       :gateway-type (:gateway-type body)
                       :allow-spectator (if (contains? body :allow-spectator)
                                          (boolean (:allow-spectator body))
                                          true)
                       :spectatorhands (boolean (:spectatorhands body))
                       :open-decklists (boolean (:open-decklists body))
                       :api-access true}]
          (on-lobby-thread (lobby/try-create-lobby uid user options))
          (if-let [lobby (app-state/uid-player->lobby uid)]
            (response 200 (assoc (lobby-brief lobby) :you (:side (lobby/player? uid lobby))))
            (response 500 {:message "Failed to create lobby (creation may be blocked)"})))))))

(defn join-handler [{body :body :as ctx}]
  (wrap-agent ctx
    (fn [uid user _ctx]
      (let [gameid (parse-gameid (:gameid body))
            lobby (when gameid (app-state/get-lobby gameid))]
        (cond
          (nil? lobby) (response 404 {:message "No such lobby"})
          (not (api-access-allowed? lobby)) (response 403 {:message "Lobby does not allow API access"})
          :else
          (let [?data {:gameid gameid
                       :request-side (:request-side body)
                       :password (:password body)}
                joined (on-lobby-thread (lobby/join-lobby! user uid ?data nil lobby))
                player (when (map? joined) (lobby/player? uid joined))]
            (if player
              (response 200 (assoc (lobby-brief joined) :you (:side player)))
              (response 403 {:message "Could not join (lobby full, wrong password, or blocked)"}))))))))

(defn watch-handler [{body :body :as ctx}]
  (wrap-agent ctx
    (fn [uid user _ctx]
      (let [gameid (parse-gameid (:gameid body))
            lobby (when gameid (app-state/get-lobby gameid))
            request-side (:request-side body)]
        (cond
          (nil? lobby) (response 404 {:message "No such lobby"})
          (not (api-access-allowed? lobby)) (response 403 {:message "Lobby does not allow API access"})
          (not (:allow-spectator lobby)) (response 403 {:message "Lobby does not allow spectators"})
          :else
          (let [result
                (on-lobby-thread
                  (let [correct-password? (lobby/check-password lobby user (:password body))
                        watch-str (str (:username user) " joined the game as a spectator"
                                       (when request-side (str " (" request-side " perspective)")) ".")
                        watch-message (make-system-message watch-str)
                        new-app-state (swap! app-state/app-state
                                             update :lobbies
                                             #(-> %
                                                  (lobby/handle-watch-lobby gameid uid user correct-password? watch-message request-side)
                                                  (lobby/handle-set-last-update gameid uid)))
                        lobby? (get-in new-app-state [:lobbies gameid])]
                    (when (and lobby? (lobby/spectator? uid lobby?))
                      (lobby/send-lobby-state lobby?)
                      (lobby/broadcast-lobby-list)
                      (when (:state lobby?)
                        (main/handle-notification (:state lobby?) watch-str))
                      lobby?)))]
            (if (map? result)
              (response 200 (assoc (lobby-brief result) :you "Spectator"))
              (response 403 {:message "Could not watch (wrong password or blocked)"}))))))))

(defn leave-handler [ctx]
  (wrap-agent ctx
    (fn [uid user {db :system/db}]
      (if-let [lobby (app-state/uid->lobby uid)]
        (do (on-lobby-thread
              (let [started? (and (:started lobby) (:state lobby))
                    lobby? (lobby/leave-lobby! db user uid nil lobby)]
                (when (and started? lobby?)
                  (game/handle-message-and-send-diffs!
                    lobby? nil nil (str (:username user) " has left the game.")))
                (lobby/broadcast-lobby-list)))
            (response 200 {:message "Left the lobby"}))
        (response 404 {:message "Not in a lobby"})))))

(defn decks-handler [ctx]
  (wrap-agent ctx
    (fn [_uid user {db :system/db}]
      (let [found (->> (mc/find-maps db "decks" {:username (:username user)})
                       (map decks/update-deck)
                       (mapv (fn [d]
                               {:_id (str (:_id d))
                                :name (:name d)
                                :identity (get-in d [:identity :title])
                                :status (:status d)})))]
        (response 200 {:decks found})))))

(defn select-deck-handler [{body :body :as ctx}]
  (wrap-agent ctx
    (fn [uid user {db :system/db}]
      (if-let [lobby (app-state/uid-player->lobby uid)]
        (let [deck-id (or (:deck-id body)
                          (some->> (mc/find-maps db "decks" {:username (:username user)})
                                   (filter #(= (str/lower-case (str (:name %)))
                                               (str/lower-case (str (:name body)))))
                                   (first)
                                   (:_id)
                                   (str)))
              raw-deck (when deck-id
                         (try (lobby/find-deck-for-user db deck-id user)
                              (catch Exception _ nil)))]
          (if-not raw-deck
            (response 404 {:message "Deck not found (pass deck-id or an exact name)"})
            (let [processed-deck (lobby/process-deck raw-deck)
                  result
                  (on-lobby-thread
                    (let [new-app-state (swap! app-state/app-state
                                               update :lobbies
                                               #(-> %
                                                    (lobby/handle-select-deck uid processed-deck)
                                                    (lobby/handle-set-last-update (:gameid lobby) uid)))
                          lobby? (get-in new-app-state [:lobbies (:gameid lobby)])]
                      (lobby/send-lobby-state lobby?)
                      (some #(and (= uid (:uid %)) (= processed-deck (:deck %)))
                            (:players lobby?))))]
              (if result
                (response 200 {:message "Deck selected" :deck (:name processed-deck)})
                (response 409 {:message "Deck rejected (not legal for this lobby's format?)"})))))
        (response 404 {:message "Not in a lobby"})))))

(defn start-handler [ctx]
  (wrap-agent ctx
    (fn [uid _user {db :system/db}]
      (if-let [lobby (app-state/uid-player->lobby uid)]
        (cond
          (:started lobby)
          (response 409 {:message "Game already started"})
          (< (count (:players lobby)) 2)
          (response 409 {:message "Need two players to start"})
          (not (lobby/first-player? uid lobby))
          (response 403 {:message "Only the lobby creator can start the game"})
          :else
          (do (on-lobby-thread (game/try-start-game db uid (:gameid lobby)))
              (if (:started (app-state/get-lobby (:gameid lobby)))
                (response 200 {:message "Game started"})
                (response 409 {:message "Could not start (do both players have decks?)"}))))
        (response 404 {:message "Not in a lobby"})))))

;;; Game endpoints

(defn state-handler
  "Full side-appropriate game state, exactly what the browser client renders.
  Pass ?since=<version>&wait-ms=<n> to long-poll until something changes."
  [{params :params :as ctx}]
  (wrap-agent ctx
    (fn [uid _user _ctx]
      (if-let [lobby (find-game-lobby uid)]
        (let [state (:state lobby)
              [persp state-key] (perspective uid lobby)
              since (:since params)
              wait-ms (try (min max-wait-ms (Long/parseLong (str (:wait-ms params "0"))))
                           (catch Exception _ 0))
              deadline (+ (System/currentTimeMillis) wait-ms)]
          (if-not persp
            (response 403 {:message "Not a participant in this game"})
            (do (while (and since
                            (= since (state-version state))
                            (< (System/currentTimeMillis) deadline))
                  (Thread/sleep 200))
                (response 200 {:gameid (str (:gameid lobby))
                               :perspective persp
                               :version (state-version state)
                               :state (get (diffs/public-states state) state-key)}))))
        (response 404 {:message "Not in a started game"})))))

(defn status-handler
  "Compact game summary: whose turn, clicks/credits, current prompt, run state,
  recent log. Enough for an agent to decide what to look at next."
  [{params :params :as ctx}]
  (wrap-agent ctx
    (fn [uid _user _ctx]
      (if-let [lobby (find-game-lobby uid)]
        (let [state (:state lobby)
              since (:since params)
              wait-ms (try (min max-wait-ms (Long/parseLong (str (:wait-ms params "0"))))
                           (catch Exception _ 0))
              deadline (+ (System/currentTimeMillis) wait-ms)]
          (while (and since
                      (= since (state-version state))
                      (< (System/currentTimeMillis) deadline))
            (Thread/sleep 200))
          (response 200 ((status-body lobby) uid)))
        (response 404 {:message "Not in a started game"})))))

(defn action-handler
  "Executes a game action: {:command <name> :args {...}}. Command names match
  game.core.process-actions/commands, i.e. exactly what the browser client
  sends. Card references in :args may be a cid string, a card title, or a map
  with a :cid key."
  [{body :body :as ctx}]
  (wrap-agent ctx
    (fn [uid _user _ctx]
      (let [lobby (find-game-lobby uid)
            player (when lobby (lobby/player? uid lobby))
            command (:command body)]
        (cond
          (not lobby)
          (response 404 {:message "Not in a started game"})
          (not player)
          (response 403 {:message "Spectators cannot act"})
          (not (contains? commands command))
          (response 400 {:message (str "Unknown command: " command)
                         :valid-commands (sort (keys commands))})
          :else
          (let [state (:state lobby)
                side (side-from-str (:side player))
                {:keys [args error]} (hydrate-args state (:side player) (:args body))]
            (if error
              (response 400 {:message error})
              (let [result
                    (on-game-thread lobby
                      (let [old-state @state]
                        (try
                          (swap! app-state/app-state
                                 update :lobbies lobby/handle-set-last-update (:gameid lobby) uid)
                          (game/update-and-send-diffs! main/handle-action lobby side command args)
                          ::ok
                          (catch Exception e
                            (reset! state old-state)
                            (or (.getMessage e) (str (class e)))))))]
                (cond
                  (= ::ok result)
                  (response 200 (assoc ((status-body lobby) uid) :message "ok"))
                  (= ::timeout result)
                  (response 504 {:message "Action timed out"})
                  :else
                  (response 500 {:message (str "Action failed: " result)}))))))))))

(defn say-handler [{body :body :as ctx}]
  (wrap-agent ctx
    (fn [uid user _ctx]
      (let [text (str (:text body ""))
            lobby (app-state/uid->lobby uid)]
        (cond
          (str/blank? text)
          (response 400 {:message "text required"})
          (nil? lobby)
          (response 404 {:message "Not in a lobby"})
          (and (:started lobby) (:state lobby))
          (let [player (lobby/player? uid lobby)
                side (if player (side-from-str (:side player)) :spectator)]
            (if (and (= side :spectator) (:mute-spectators lobby))
              (response 403 {:message "Spectators are muted in this game"})
              (do (on-game-thread lobby
                    (game/handle-message-and-send-diffs! lobby side user text))
                  (response 200 {:message "ok"}))))
          :else
          (do (on-lobby-thread
                (let [message (make-message {:user user :text text})
                      new-app-state (swap! app-state/app-state
                                           update :lobbies
                                           lobby/handle-send-message (:gameid lobby) message)]
                  (lobby/send-lobby-state (get-in new-app-state [:lobbies (:gameid lobby)]))))
              (response 200 {:message "ok"})))))))

(defn concede-handler [ctx]
  (wrap-agent ctx
    (fn [uid _user _ctx]
      (let [lobby (find-game-lobby uid)
            player (when lobby (lobby/player? uid lobby))]
        (cond
          (not lobby) (response 404 {:message "Not in a started game"})
          (not player) (response 403 {:message "Spectators cannot concede"})
          :else
          (let [side (side-from-str (:side player))]
            (on-game-thread lobby
              (game/update-and-send-diffs! main/handle-concede lobby side))
            (response 200 {:message "Conceded"})))))))
