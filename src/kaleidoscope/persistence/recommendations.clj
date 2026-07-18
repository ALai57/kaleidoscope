(ns kaleidoscope.persistence.recommendations
  (:require [honey.sql :as hsql]
            [honey.sql.helpers :as hh]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]))

(defn create-recommendations!
  "Shelve a batch of curated candidates for an interest. Every row starts as
  status=shelved; origin comes from the candidate (trusted | novel)."
  [db interest-id candidates]
  (if (empty? candidates)
    []
    (let [now (utils/now)]
      (rdbms/insert! db
                     :recommendations
                     (mapv (fn [{:keys [kind title source url est-time why origin]}]
                             {:id          (utils/uuid)
                              :interest-id interest-id
                              :kind        kind
                              :title       title
                              :source      source
                              :url         url
                              :est-time    est-time
                              :why         why
                              :origin      (or origin "novel")
                              :status      "shelved"
                              :added-at    now})
                           candidates)
                     :ex-subtype :UnableToCreateRecommendation))))

(defn get-recommendations
  "Return recommendations for an interest, newest first, optionally filtered
  by status and/or kind."
  [db interest-id {:keys [status kind]}]
  (let [hostname (tenant/hostname-of db)]
    (next/execute! (tenant/unwrap db)
                   (hsql/format {:select   :*
                                 :from     :recommendations
                                 :where    (cond-> [:and [:= :interest-id interest-id]]
                                             status   (conj [:= :status status])
                                             kind     (conj [:= :kind kind])
                                             hostname (conj [:= :hostname hostname]))
                                 :order-by [[:added-at :desc]]})
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn archive-shelved!
  "Archive everything currently shelved for an interest. The shelf is finite:
  each curation run replaces it rather than growing it without bound."
  [db interest-id]
  (let [hostname (tenant/hostname-of db)]
    (next/execute! (tenant/unwrap db)
                   (hsql/format (-> (hh/update :recommendations)
                                    (hh/set {:status "archived"})
                                    (hh/where (cond-> [:and
                                                       [:= :interest-id interest-id]
                                                       [:= :status "shelved"]]
                                                hostname (conj [:= :hostname hostname])))))
                   {:builder-fn rs/as-unqualified-kebab-maps})))

(defn update-recommendation-status!
  "Update a recommendation's status, scoped to interest-id. Returns nil if not
  found or not in that interest — indistinguishable by design."
  [db recommendation-id interest-id status]
  (first (rdbms/scoped-update! db
                               :recommendations
                               {:id recommendation-id :interest-id interest-id}
                               {:status status})))
