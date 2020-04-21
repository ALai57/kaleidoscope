(ns andrewslai.clj.persistence.postgres-test
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]))


;; https://github.com/whostolebenfrog/lein-postgres
;; https://eli.naeher.name/embedded-postgres-in-clojure/

(defn find-user-index [username v]
  (keep-indexed (fn [idx user] (when (= username (:username user)) idx))
                v))

(defn find-index [where-clause db]
  (let [k (first (keys where-clause))
        v (where-clause k)]
    (keep-indexed (fn [idx user] (when (= v (user k)) idx))
                  db)))

(extend-type clojure.lang.IAtom
  users/UserPersistence
  (create-user! [a user]
    (users/-create-user! a user))
  (create-login! [a id password]
    (users/-create-login! a id password))
  (register-user! [a user password]
    (users/-register-user-impl! a user password))
  (update-user [a username update-map]
    (users/-update-user! a username update-map))
  (get-user-by-id [a user-id]
    (users/-get-user-by-id a user-id))
  (get-user [a username]
    (users/-get-user a username))
  (get-password [a user-id]
    (users/-get-password-2 a user-id))
  (verify-credentials [a credentials]
    (users/-verify-credentials a credentials))
  (delete-user! [a {:keys [username password] :as credentials}]
    (when (users/verify-credentials a credentials)
      (let [{:keys [id]} (users/get-user a username)
            updated-users (remove #(= username (:username %)) (:users @a))
            updated-login (remove #(= id (:id %)) (:logins @a))]
        (swap! a assoc :users updated-users)
        (if (users/get-user a username) 0 1))))
  (login [a credentials]
    (users/-login a credentials))

  postgres/RelationalDatabase
  (select [a table where]
    (let [k (first (keys where))
          v (where k)]
      (filter #(= v (k %)) ((keyword table) (deref a)))))
  (update! [a table payload where]
    (let [idx (first (find-index where (:users @a)))]
      (swap! a update-in [:users idx] merge payload)
      [1]))
  (insert! [a table payload]
    (swap! a update (keyword table) conj payload)))

(extend-protocol articles/ArticlePersistence
  clojure.lang.IAtom
  (get-all-articles [a]
    (:articles (deref a)))
  (get-article-metadata [a article-name]
    (first (filter #(= article-name (:article_url %))
                   (:metadata (deref a)))))
  (get-article-content [a article-id]
    (first (filter #(= article-id (:article_id %))
                   (:content (deref a)))))
  (get-full-article [a article-name]
    (articles/-get-full-article a article-name))
  (get-resume-info [a]
    (:resume-info (deref a))))

(comment
  (users/register-user! test-user-db
                        {:first_name "Andrew"
                         :last_name "Lai"
                         :email "me@andrewslai.com"
                         :username "Andrew"
                         :avatar (byte-array (map (comp byte int) "Hello world!"))}
                        "password")

  @test-user-db
  (users/get-user test-user-db "new-user")
  )
