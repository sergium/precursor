(ns pc.http.sente
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.auth :as auth]
            [pc.datomic :as pcd]
            [pc.email :as email]
            [pc.http.datomic2 :as datomic2]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.access-request :as access-request-model]
            [pc.models.chat :as chat]
            [pc.models.cust :as cust]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer]
            [pc.models.permission :as permission-model]
            [pc.utils :as utils]
            [slingshot.slingshot :refer (try+ throw+)]
            [taoensso.sente :as sente])
  (:import java.util.UUID))

;; TODO: find a way to restart sente
(defonce sente-state (atom {}))

(defn uuid
  []
  (UUID/randomUUID))

(defn user-id-fn [req]
  (-> req :cookies (get "prcrsr-client-id") :value))

;; hash-map of document-id to connected users
;; Used to keep track of which transactions to send to which user
;; sente's channel handling stuff is not much fun to work with :(
;; e.g {:12345 {:uuid-1 {show-mouse?: true} :uuid-1 {:show-mouse? false}}}
(defonce document-subs (atom {}))

(defn notify-transaction [data]
  ;; TODO: store client-uuid as a proper uuid everywhere
  (doseq [[uid _] (dissoc (get @document-subs (:document/id data)) (str (:session/uuid data)))]
    (log/infof "notifying %s about new transactions for %s" uid (:document/id data))
    ((:send-fn @sente-state) uid [:datomic/transaction data]))
  (when-let [server-timestamps (seq (filter #(= :server/timestamp (:a %)) (:tx-data data)))]
    (log/infof "notifying %s about new server timestamp for %s" (:session/uuid data) (:document/id data))
    ((:send-fn @sente-state) (str (:session/uuid data)) [:datomic/transaction (assoc data :tx-data server-timestamps)])))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

(defn client-uuid->uuid
  "Get the client's user-id from the client-uuid"
  [client-uuid]
  (str/replace client-uuid #"-[^-]+$" ""))

(defn check-document-access-from-auth [doc-id req scope]
  (let [doc (doc-model/find-by-id (:db req) doc-id)]
    (when-not (auth/has-document-permission? (:db req) doc (-> req :ring-req :auth) scope)
      (if (auth/logged-in? (:ring-req req))
        (throw+ {:status 403
                 :error-msg "This document is private. Please request access."
                 :error-key :document-requires-invite})
        (throw+ {:status 401
                 :error-msg "This document is private. Please log in to access it."
                 :error-key :document-requires-login})))))

;; TODO: make sure to kick the user out of subscribed if he loses access
(defn check-subscribed [doc-id req scope]
  ;; TODO: we're making a simplifying assumption that subscribed == :admin
  ;;       That needs to be fixed at some point
  (when (= scope :admin)
    (get-in @document-subs [doc-id (-> req :client-uuid client-uuid->uuid)])))

;; TODO: this should take an access level at some point
(defn check-document-access [doc-id req scope]
  (or (check-subscribed doc-id req scope)
      (check-document-access-from-auth doc-id req scope)))

(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (log/infof "%s for %s" (:event req) (:client-uuid req)))

(defn clean-document-subs [uuid]
  (swap! document-subs (fn [ds]
                         ;; Could be optimized...
                         (reduce (fn [acc [document-id user-ids]]
                                   (if-not (contains? user-ids uuid)
                                     acc
                                     (let [new-user-ids (dissoc user-ids uuid)]
                                       (if (empty? new-user-ids)
                                         (dissoc acc document-id)
                                         (assoc acc document-id new-user-ids)))))
                                 ds ds))))

(defn close-connection [client-uuid]
  (log/infof "closing connection for %s" client-uuid)
  (let [uuid (client-uuid->uuid client-uuid)]
    (doseq [uid (reduce (fn [acc [doc-id clients]]
                          (if (contains? clients uuid)
                            (set/union acc (keys clients))
                            acc))
                        #{} @document-subs)]
      (log/infof "notifying %s about %s leaving" uid uuid)
      ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-uuid uuid}]))
    (clean-document-subs uuid)))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-uuid] :as req}]
  (close-connection client-uuid))

(defmethod ws-handler :frontend/close-connection [{:keys [client-uuid] :as req}]
  (close-connection client-uuid))

(defmethod ws-handler :frontend/unsubscribe [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document-id) req :admin)
  (let [document-id (-> ?data :document-id)
        cid (client-uuid->uuid client-uuid)]
    (log/infof "unsubscribing %s from %s" client-uuid document-id)
    (swap! document-subs update-in [document-id] dissoc cid)
    (doseq [[uid _] (get @document-subs document-id)]
      ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-uuid cid}]))))

(def colors
  #{"#1abc9c"
    "#2ecc71"
    "#3498db"
    "#9b59b6"
    "#16a085"
    "#27ae60"
    "#2980b9"
    "#8e44ad"
    "#f1c40f"
    "#e67e22"
    "#e74c3c"
    "#f39c12"
    "#d35400"
    "#c0392b"
    ;;"#ecf0f1"
    ;;"#bdc3c7"
    })

(defn subscribe-to-doc [document-id uuid cust-name]
  (swap! document-subs update-in [document-id]
         (fn [subs]
           (let [available-colors (or (seq (apply disj colors (map :color (vals subs))))
                                      (seq colors))]
             (-> subs
                 (update-in [uuid :color] (fn [c] (or c (rand-nth available-colors))))
                 (assoc-in [uuid :cust-name] cust-name)
                 (assoc-in [uuid :show-mouse?] true))))))

;; TODO: subscribe should be the only function you need when you get to a doc, then it should send
;;       all of the data asynchronously
(defmethod ws-handler :frontend/subscribe [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document-id) req :admin)
  (let [document-id (-> ?data :document-id)
        cid (client-uuid->uuid client-uuid)
        send-fn (:send-fn @sente-state)]
    (log/infof "subscribing %s to %s" client-uuid document-id)

    (subscribe-to-doc document-id (client-uuid->uuid client-uuid)
                      (-> req :ring-req :auth :cust :cust/name))
    (doseq [[uid _] (get @document-subs document-id)]
      (send-fn uid [:frontend/subscriber-joined (merge {:client-uuid cid}
                                                       (get-in @document-subs [document-id cid]))]))

    ;; TODO: we'll need a read-api or something here at some point
    (log/infof "sending document for %s to %s" document-id cid)
    (send-fn (str cid) [:frontend/db-entities
                        {:document/id document-id
                         :entities [(pcd/touch+ (doc-model/find-by-id (:db req) document-id))]
                         :entity-type :document}])

    (log/infof "sending layers for %s to %s" document-id cid)
    (send-fn (str cid) [:frontend/db-entities
                        {:document/id document-id
                         :entities (layer/find-by-document (:db req) {:db/id document-id})
                         :entity-type :layer}])
    (log/infof "sending chats %s to %s" document-id cid)
    (send-fn (str cid) [:frontend/db-entities
                        {:document/id document-id
                         :entities (chat/find-by-document (:db req) {:db/id document-id})
                         :entity-type :chat}])
    (log/infof "sending subscribers for %s to %s" document-id cid)
    (send-fn (str cid) [:frontend/subscribers
                        {:document/id document-id
                         :subscribers (get @document-subs document-id)}])

    ;; These are interesting b/c they're read-only. And by "interesting", I mean "bad"
    ;; We should find a way to let the frontend edit things
    ;; TODO: only send this stuff when it's needed
    (log/infof "sending permission-data for %s to %s" document-id cid)
    (send-fn (str cid) [:frontend/db-entities
                        {:document/id document-id
                         :entities (map (partial permission-model/read-api (:db req))
                                        (permission-model/find-by-document (:db req)
                                                                           {:db/id document-id}))
                         :entity-type :permission}])
    (send-fn (str cid) [:frontend/db-entities
                        {:document/id document-id
                         :entities (map access-grant-model/read-api
                                        (access-grant-model/find-by-document (:db req)
                                                                             {:db/id document-id}))
                         :entity-type :access-grant}])

    (send-fn (str cid) [:frontend/db-entities
                        {:document/id document-id
                         :entities (map (partial access-request-model/read-api (:db req))
                                        (access-request-model/find-by-document (:db req)
                                                                               {:db/id document-id}))
                         :entity-type :access-request}])))

(defmethod ws-handler :frontend/fetch-created [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [;; TODO: at some point we may want to limit, but it's just a
          ;; list of longs, so meh
          ;; limit (get ?data :limit 100)
          ;; offset (get ?data :offset 0)
          doc-ids (doc-model/find-created-by-cust (:db req) cust)]
      (log/infof "fetching created for %s" client-uuid)
      (?reply-fn {:docs (map (fn [doc-id] {:db/id doc-id
                                           :last-updated-instant (doc-model/last-updated-time (:db req) doc-id)})
                             doc-ids)}))))

(defmethod ws-handler :frontend/fetch-touched [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [;; TODO: at some point we may want to limit, but it's just a
          ;; list of longs, so meh
          ;; limit (get ?data :limit 100)
          ;; offset (get ?data :offset 0)
          doc-ids (doc-model/find-touched-by-cust (:db req) cust)]
      (log/infof "fetching touched for %s" client-uuid)
      (?reply-fn {:docs (map (fn [doc-id] {:db/id doc-id
                                           :last-updated-instant (doc-model/last-updated-time (:db req) doc-id)})
                             doc-ids)}))))

(defmethod ws-handler :frontend/transaction [{:keys [client-uuid ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [document-id (-> ?data :document/id)
        datoms (->> ?data
                 :datoms
                 (remove (comp nil? :v))
                 ;; Don't let people sneak layers into other documents
                 (map (fn [d] (if (= :document/id (:a d))
                                (assoc d :v document-id)
                                d))))
        _ (def datoms datoms)
        cust-uuid (-> req :ring-req :auth :cust :cust/uuid)]
    (log/infof "transacting %s on %s for %s" datoms document-id client-uuid)
    (datomic2/transact! datoms
                        document-id
                        (UUID/fromString (client-uuid->uuid client-uuid))
                        cust-uuid)))

(defmethod ws-handler :frontend/mouse-position [{:keys [client-uuid ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [document-id (-> ?data :document/id)
        mouse-position (-> ?data :mouse-position)
        tool (-> ?data :tool)
        layers (-> ?data :layers)
        cid (client-uuid->uuid client-uuid)]
    (doseq [[uid _] (dissoc (get @document-subs document-id) cid)]
      ((:send-fn @sente-state) uid [:frontend/mouse-move (merge
                                                          {:client-uuid cid
                                                           :tool tool
                                                           :layers layers}
                                                          (when mouse-position
                                                            {:mouse-position mouse-position}))]))))

(defmethod ws-handler :frontend/update-self [{:keys [client-uuid ?data] :as req}]
  ;; TODO: update subscribers in a different way
  (check-document-access (-> ?data :document/id) req :admin)
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [doc-id (-> ?data :document/id)
          cid (client-uuid->uuid client-uuid)]
      (log/infof "updating self for %s" (:cust/uuid cust))
      (let [new-cust (cust/update! cust {:cust/name (:cust/name ?data)})]
        ;; TODO: name shouldn't be stored here, it should be in the client-side db
        (swap! document-subs utils/update-when-in [doc-id (str cid)] assoc :cust-name (:cust/name new-cust))
        (doseq [[uid _] (get @document-subs doc-id)]
          ;; TODO: use update-subscriber for everything
          ((:send-fn @sente-state) uid [:frontend/update-subscriber
                                        {:client-uuid cid
                                         :subscriber-data {:cust-name (:cust/name new-cust)}}]))))))

(defmethod ws-handler :frontend/send-invite [{:keys [client-uuid ?data ?reply-fn] :as req}]
  ;; This may turn out to be a bad idea, but error handling is done through creating chats
  (check-document-access (-> ?data :document/id) req :admin)
  (let [[chat-id] (pcd/generate-eids (pcd/conn) 1)
        doc-id (-> ?data :document/id)
        invite-loc (-> ?data :invite-loc)
        notify-invite (fn [body]
                        (if (= :overlay invite-loc)
                          ((:send-fn @sente-state) (str (client-uuid->uuid client-uuid))
                           [:frontend/invite-response {:document/id doc-id
                                                       :response body}])
                          @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                                    :document/id doc-id}
                                                   {:chat/body body
                                                    :server/timestamp (java.util.Date.)
                                                    :document/id doc-id
                                                    :db/id chat-id
                                                    :cust/uuid (auth/prcrsr-bot-uuid (:db req))
                                                    ;; default bot color, also used on frontend chats
                                                    :chat/color "#00b233"}])))]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [email (-> ?data :email)
            cid (client-uuid->uuid client-uuid)]
        (log/infof "%s sending an email to %s on doc %s" (:cust/email cust) email doc-id)
        (try
          (email/send-chat-invite {:cust cust :to-email email :doc-id doc-id})
          (notify-invite (str "Invite sent to " email))
          (catch Exception e
            (log/error e)
            (.printStackTrace e)
            (notify-invite (str "Sorry! There was a problem sending the invite to " email)))))

      (notify-invite "Please sign up to send an invite."))))

(defmethod ws-handler :frontend/send-permission-grant [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [email (-> ?data :email)
            cid (client-uuid->uuid client-uuid)
            annotations {:document/id doc-id
                         :cust/uuid (:cust/uuid cust)
                         :transaction/broadcast true}]
        (if-let [grantee (cust/find-by-email (:db req) email)]
          (permission-model/grant-permit {:db/id doc-id}
                                         grantee
                                         :permission.permits/admin
                                         annotations)
          (access-grant-model/grant-access {:db/id doc-id}
                                           email
                                           cust
                                           annotations)))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :frontend/grant-access-request [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [request (some->> ?data :request-id (access-request-model/find-by-id (:db req)))]
      (let [cid (client-uuid->uuid client-uuid)
            cust (-> req :ring-req :auth :cust)
            annotations {:document/id doc-id
                         :cust/uuid (:cust/uuid cust)
                         :transaction/broadcast true}]
        ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
        ;;       Right now it's too easy to accidentally forget to check.
        (assert (= doc-id (:access-request/document request)))
        (permission-model/convert-access-request request annotations))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :frontend/deny-access-request [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [request (some->> ?data :request-id (access-request-model/find-by-id (:db req)))]
      (let [cid (client-uuid->uuid client-uuid)
            cust (-> req :ring-req :auth :cust)
            annotations {:document/id doc-id
                         :cust/uuid (:cust/uuid cust)
                         :transaction/broadcast true}]
        ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
        ;;       Right now it's too easy to accidentally forget to check.
        (assert (= doc-id (:access-request/document request)))
        (access-request-model/deny-request request annotations))
      (comment (notify-invite "Please sign up to send an invite.")))))

;; TODO: don't send request if they already have access
(defmethod ws-handler :frontend/send-permission-request [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (let [doc-id (-> ?data :document/id)]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (if-let [doc (doc-model/find-by-id (:db req) doc-id)]
        (let [email (-> ?data :email)
              cid (client-uuid->uuid client-uuid)
              annotations {:document/id doc-id
                           :cust/uuid (:cust/uuid cust)
                           :transaction/broadcast true}]
          (let [{:keys [db-after]} (access-request-model/create-request doc cust annotations)]
            ;; have to send it manually to the requestor b/c user won't be subscribed
            ((:send-fn @sente-state) (str cid) [:frontend/db-entities
                                                {:document/id doc-id
                                                 :entities (map (partial access-request-model/read-api db-after)
                                                                (access-request-model/find-by-doc-and-cust db-after doc cust))
                                                 :entity-type :access-request}]))))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :chsk/ws-ping [req]
  ;; don't log
  nil)

(defn handle-req [req]
  (try+
   (ws-handler (assoc req :db (pcd/default-db)))
   (catch :status t
     (let [send-fn (:send-fn @sente-state)]
       (log/error t)
       ;; TODO: should this use the send-fn? We can do that too, I guess, inside of the defmethod.
       ;; TODO: rip out sente and write a sensible library
       (send-fn (str (client-uuid->uuid (:client-uuid req))) [:frontend/error {:status-code (:status t)
                                                              :error-msg (:error-msg t)
                                                              :event (:event req)
                                                              :event-data (:?data req)}])))))

(defn setup-ws-handlers [sente-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (async/go-loop []
                   (when-let [req (async/<! tap)]
                     (utils/straight-jacket (handle-req req))
                     (recur)))))

(defn init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket! {:user-id-fn #'user-id-fn})]
    (reset! sente-state fns)
    (setup-ws-handlers fns)
    fns))
