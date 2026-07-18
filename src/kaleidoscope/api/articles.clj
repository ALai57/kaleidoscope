(ns kaleidoscope.api.articles
  (:require [clojure.set :as set]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.api.groups :as api.groups]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-articles
  (rdbms/make-finder :articles))

(defn create-article!
  [db {:keys [article-url article-title] :as article}]
  (log/infof "Creating Article: %s" article)
  (let [now    (utils/now)
        result (rdbms/insert! db
                              :articles (cond-> (assoc article
                                                       :created-at  now
                                                       :modified-at now)
                                          (nil? article-title) (assoc :article-title article-url))
                              :ex-subtype :UnableToCreateArticle)]
    (log/infof "Created Article: %s" result)
    result))

(defn update-article!
  [db {:keys [id public-visibility] :as article}]
  (log/infof "Updating Article: %s" article)
  (let [now    (utils/now)
        result (rdbms/update! db
                              :articles   (assoc article :modified-at now)
                              :ex-subtype :UnableToCreateArticle)]
    (log/infof "Updated Article: %s" result)
    result))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Branches
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-branches
  (rdbms/make-finder :full-branches))

(defn create-branch!
  "Create a branch, optionally attaching it to an existing article by
  :article-id. Verified exploitable 2026-07-03 (see PLAN.md): the
  article-id lookup used to be unscoped by hostname — a writer authorized
  for *any* site could supply any other site's existing article-id and
  attach a new branch to it, entirely outside the site they're actually
  authorized for. Returns nil if article-id is given but doesn't resolve
  to an article under the given hostname."
  [db {:keys [article-id author branch-name hostname] :as article-branch}]
  (log/infof "Creating branch: %s" article-branch)
  (next/with-transaction [tx db]
    (let [now    (utils/now)
          [{article-id :id :as article}] (if article-id
                                           (get-articles tx {:id article-id :hostname hostname})
                                           (create-article! tx (select-keys article-branch [:author :article-url :article-tags :hostname :article-title])))]
      (when article-id
        (let [[{branch-id :id :as branch}] (rdbms/insert! tx
                                                          :article-branches {:branch-name branch-name
                                                                             :article-id  article-id
                                                                             :hostname    (:hostname article)
                                                                             :created-at  now
                                                                             :modified-at now}
                                                          :ex-subtype :UnableToCreateArticleBranch)
              result                       (get-branches tx {:branch-id branch-id})]
          (log/infof "Created Article Branch: %s" result)
          result)))))

(defn publish-branch!
  "Publish a branch, scoped to hostname. Verified exploitable 2026-07-03
  (see PLAN.md): the branch lookup used to take a bare branch-id with no
  hostname check anywhere in the call chain, and the HTTP handler resolved
  branch-id from :article-url + :branch-name alone (also no hostname
  check) — a writer authorized for *any* site could force-publish another
  site's private draft article by url + branch name, with no need to be
  authorized for that site at all. Returns nil if not found under that
  hostname."
  ([db branch-id hostname]
   (publish-branch! db branch-id hostname (utils/now)))
  ([db branch-id hostname now]
   (log/infof "Publishing Branch: %s" branch-id)
   (when (seq (get-branches db {:branch-id branch-id :hostname hostname}))
     (rdbms/update! db :article-branches
                    {:published-at now
                     :id           branch-id}
                    :ex-subtype :UnableToPublishBranch)
     (let [result (get-branches db {:branch-id branch-id})]
       (log/infof "Published Branch: %s" result)
       result))))

(defn unpublish-branch!
  "Unpublish a branch, scoped to hostname — see publish-branch! for why."
  [db branch-id hostname]
  (log/infof "Unpublishing Branch: %s" branch-id)
  (when (seq (get-branches db {:branch-id branch-id :hostname hostname}))
    (rdbms/update! db :article-branches
                   {:published-at nil
                    :id           branch-id}
                   :ex-subtype :UnableToUnPublishBranch)
    (let [result (get-branches db {:branch-id branch-id})]
      (log/infof "Unpublished Branch: %s" result)
      result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Versions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-versions
  (rdbms/make-finder :full-versions))

(defn create-version!
  [db {:keys [branch-id] :as article-branch} {:keys [created-at] :as article-version}]
  (let [[{branch-id :branch-id
          hostname  :hostname}]        (get-branches db (if branch-id
                                                          {:branch-id branch-id}
                                                          article-branch))
        now                            (or created-at (utils/now))
        [{version-id :id :as version}] (rdbms/insert! db
                                                      :article-versions (assoc article-version
                                                                               :branch-id   branch-id
                                                                               :hostname    hostname
                                                                               :created-at  now
                                                                               :modified-at now)
                                                      :ex-subtype :UnableToCreateArticleBranch)

        result (get-versions db {:version-id version-id})]
    (log/infof "Created Article version: %s" result)
    result))

(defn published?
  [branch]
  (:published-at branch))

(defn new-version!
  [db article-branch article-version]
  (let [existing-branch (first (get-branches db (select-keys article-branch [:article-url :hostname])))]
    (if (published? existing-branch)
      (throw (ex-info "Cannot change a published branch" existing-branch))
      (next/with-transaction [tx db]
        (when-not existing-branch
          (create-branch! tx article-branch))
        (let [[branch] (get-branches tx (select-keys article-branch [:article-url :hostname]))]
          (create-version! tx branch article-version))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Audiences:
;; - Attach a group to an article
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-article-audiences
  (rdbms/make-finder :full-article-audiences))

(defn- -add-audiences-to-article!
  [db article-id hostname group-ids]
  (log/infof "Adding an audience to article %s" article-id)
  (let [now (utils/now)]
    (rdbms/insert! db
                   :article-audiences (for [group-id group-ids
                                            :let     [id (utils/uuid)]]
                                        {:id         id
                                         :group-id   (str group-id)
                                         :article-id article-id
                                         :hostname   hostname
                                         :created-at now})
                   :ex-subtype :UnableToAddArticleAudience)))

(defn add-audience-to-article!
  [db {:keys [id] :as article} group]
  ;; TODO: only go to DB after we know we have to
  (let [existing-audience (get-article-audiences db {:article-id (:id article)
                                                     :group-id   (str (:id group))})]
    (cond
      (nil? (:hostname article))          (log/warnf "Article is missing hostname %s" article)
      (empty? (get-articles db article)) (log/warnf "No articles matching %s" article)

      ;; Only attempt to create an audience if it doesn't exist.
      ;; This is a workaround so I don't have to deal with a multiple upsert in both
      ;; HSQL and Postgres
      (not-empty existing-audience) existing-audience
      (empty? existing-audience)    (-add-audiences-to-article! db (:id article) (:hostname article) [(:id group)]))))

(defn delete-article-audience!
  [database audience-id]
  (log/infof "Deleting Article Audience: %s" audience-id)
  (rdbms/delete! database
                 :article-audiences audience-id
                 :ex-subtype :UnableToDeleteArticleAudience))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Published articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def -get-published-articles
  (rdbms/make-finder :published-articles))

;; TODO: Hostname should probably be added to groups too
(defn get-published-articles
  "Query should have {:hostname , :other...}"
  [db {:keys [hostname article-url] :as query} {:keys [email] :as _user}]
  (let [article-id (when article-url
                     (:id (first (get-articles db {:article-url article-url
                                                   :hostname    hostname}))))

        users-groups (->> {:email email}
                          (api.groups/get-group-memberships db)
                          (map :group-id)
                          (into #{}))
        articles     (->> {:hostname hostname}
                          (get-article-audiences db))

        public-article-ids     (mapv :id (get-articles db {:public-visibility true
                                                           :hostname          hostname}))
        restricted-article-ids (->> articles
                                    (filter (fn [{:keys [group-id article-id public-visibility] :as audience}]
                                              (contains? users-groups group-id)))
                                    (mapv :article-id))

        allowed-article-ids (into #{} (concat public-article-ids restricted-article-ids))]
    (log/infof "User `%s` is allowed to view article-ids %s" email allowed-article-ids)
    (cond
      (and article-url
           (contains? allowed-article-ids article-id)) (-get-published-articles db {:article-id article-id
                                                                                    :hostname   hostname})
      article-url                                      []
      (not-empty allowed-article-ids)                  (-get-published-articles db {:article-id allowed-article-ids
                                                                                    :hostname   hostname})
      :else                                            [])))
