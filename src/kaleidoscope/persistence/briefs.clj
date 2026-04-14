(ns kaleidoscope.persistence.briefs
  (:require [honey.sql :as hsql]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]))

(defn get-latest-brief
  "Return the highest-version brief for a project, or nil if none exists."
  [db project-id]
  (first (next/execute! db
                        (hsql/format {:select   :*
                                      :from     :project-briefs
                                      :where    [:= :project-id project-id]
                                      :order-by [[:version :desc]]
                                      :limit    1})
                        {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-brief-by-version
  "Return a specific brief version for a project."
  [db project-id version]
  (first (next/execute! db
                        (hsql/format {:select :*
                                      :from   :project-briefs
                                      :where  [:and
                                               [:= :project-id project-id]
                                               [:= :version version]]})
                        {:builder-fn rs/as-unqualified-kebab-maps})))

(defn create-brief!
  "Create a new brief version. Atomically computes the next version number.
   source is 'initial', 'advisor_refinement', or 'user_clarification'.
   agent-type is set when source is 'advisor_refinement'.
   workflow-round-id links the brief to the round that produced it."
  [db {:keys [project-id content source agent-type workflow-round-id]}]
  (next/with-transaction [tx db]
    (let [result (first (next/execute! tx
                                       (hsql/format {:select [[[:coalesce [:max :version] 0] :max-version]]
                                                     :from   :project-briefs
                                                     :where  [:= :project-id project-id]})
                                       {:builder-fn rs/as-unqualified-kebab-maps}))
          next-v (inc (:max-version result 0))]
      (first (rdbms/insert! tx
                            :project-briefs
                            {:id                (utils/uuid)
                             :project-id        project-id
                             :version           next-v
                             :content           content
                             :source            source
                             :agent-type        agent-type
                             :workflow-round-id workflow-round-id
                             :created-at        (utils/now)}
                            :ex-subtype :UnableToCreateBrief)))))
