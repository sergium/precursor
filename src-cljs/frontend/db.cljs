(ns frontend.db
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close! put!]]
            [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.sente :as sente]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer (dissoc-in)]
            [taoensso.sente]))

(def schema {:layer/child {:db/cardinality :db.cardinality/many}
             :layer/points-to {:db/cardinality :db.cardinality/many
                               :db/type :db.type/ref}})

(defonce listeners (atom {}))

(defn make-initial-db [initial-entities]
  (let [conn (d/create-conn schema)]
    (d/transact! conn initial-entities)
    conn))

(defn reset-db! [db-atom initial-entities]
  (reset! db-atom @(make-initial-db initial-entities))
  db-atom)

(defn setup-listener! [db key comms sente-event annotations undo-state sente-state]
  (d/listen!
   db
   key
   (fn [tx-report]
     ;; TODO: figure out why I can send tx-report through controls ch
     ;; (cast! :db-updated {:tx-report tx-report})
     (let [[eids attrs] (reduce (fn [[eids attrs] datom]
                                  [(conj eids (:e datom))
                                   (conj attrs (:a datom))])
                                [#{} #{}] (:tx-data tx-report))]
       (doseq [eid eids
               [k callback] (get-in @listeners [db :entity-listeners (str eid)])]
         (callback tx-report))
       (doseq [attr attrs
               [k callback] (get-in @listeners [db :attribute-listeners attr])]
         (callback tx-report)))
     (when (first (filter #(= :server/timestamp (:a %)) (:tx-data tx-report)))
       (put! (:controls comms) [:chat-db-updated []]))
     (when (-> tx-report :tx-meta :can-undo?)
       (swap! undo-state update-in [:transactions] conj tx-report)
       (when-not (-> tx-report :tx-meta :undo)
         (swap! undo-state assoc-in [:last-undo] nil)))
     (when-not (or (-> tx-report :tx-meta :server-update)
                   (-> tx-report :tx-meta :bot-layer))
       (let [datoms (->> tx-report :tx-data (mapv ds/datom-read-api))]
         (doseq [datom-group (partition-all 1000 datoms)]
           (sente/send-msg sente-state [sente-event (merge {:datoms datom-group}
                                                           annotations)]
                           30000
                           (fn [reply]
                             (if (taoensso.sente/cb-success? reply)
                               (when-let [rejects (seq (:rejected-datoms reply))]
                                 (put! (:errors comms) [:datascript/rejected-datoms {:rejects rejects
                                                                                     :sente-event sente-event
                                                                                     :sent-datoms datom-group}]))
                               (put! (:errors comms) [:datascript/sync-tx-error {:reason reply
                                                                                 :sente-event sente-event
                                                                                 :sent-datoms datom-group}]))))))))))

(defn empty-db? [db]
  (empty? (d/datoms db :eavt)))

(defn generate-entity-id
  "Assumes that each entity has a different remainder and that all clients have
   the same multiple.
   Takes a db to guard against duplicate ids, but is not meant to be a protection
   against races. Only guards against a previous client with the same remainder skipping
   ids (probably due to an error).
   Returns a map of :entity-id and new :frontend-id-state"
  [db {:keys [multiple remainder next-id] :as frontend-id-state}]
  (let [eid (loop [res next-id]
              (if (first (d/datoms db :eavt res))
                (recur (+ res multiple))
                res))]
    {:entity-id eid
     :frontend-id-state (assoc frontend-id-state :next-id (+ eid multiple))}))

(defn generate-entity-ids
  "Same as generate-entity-id, but returns a list of :entity-ids"
  [db id-count frontend-id-state]
  (loop [i 0
         entity-ids []
         id-state frontend-id-state]
    (if (>= i id-count)
      {:entity-ids entity-ids
       :frontend-id-state id-state}
      (let [res (generate-entity-id db id-state)]
        (recur (inc i)
               (conj entity-ids (:entity-id res))
               (:frontend-id-state res))))))

(defn get-entity-id
  "Provides an app-state-aware API for generating entity ids, returns a map
   with :entity-id and :state keys. Backwards compatible with dummy ids."
  [app-state]
  (let [{:keys [entity-id frontend-id-state]} (generate-entity-id @(:db app-state) (:frontend-id-state app-state))]
    {:entity-id entity-id :state (assoc app-state :frontend-id-state frontend-id-state)}))

(defn get-entity-ids
  "Provides an app-state-aware API for generating entity ids, returns a map
   with :entity-id and :state keys. Backwards compatible with dummy ids."
  [app-state n]
  (let [{:keys [entity-ids frontend-id-state]} (generate-entity-ids @(:db app-state) n (:frontend-id-state app-state))]
    {:entity-ids entity-ids :state (assoc app-state :frontend-id-state frontend-id-state)}))

(defn add-entity-listener [conn eid key callback]
  (swap! listeners assoc-in [conn :entity-listeners (str eid) key] callback))

(defn add-attribute-listener [conn attribute key callback]
  (swap! listeners assoc-in [conn :attribute-listeners attribute key] callback))

(defn remove-entity-listener [conn eid key]
  (swap! listeners dissoc-in [conn :entity-listeners (str eid) key]))

(defn remove-attribute-listener [conn attribute key]
  (swap! listeners dissoc-in [conn :attribute-listeners attribute key]))
