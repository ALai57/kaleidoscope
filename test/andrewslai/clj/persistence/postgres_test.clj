(ns andrewslai.clj.persistence.postgres-test
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.persistence.core :refer [ArticlePersistence]]
            [andrewslai.clj.persistence.postgres :as postgres]))


;; https://github.com/whostolebenfrog/lein-postgres
;; https://eli.naeher.name/embedded-postgres-in-clojure/

(defn find-user-index [username v]
  (keep-indexed (fn [idx user] (when (= username (:username user)) idx))
                v))

(extend-protocol users/UserPersistence
  clojure.lang.IAtom
  (create-user! [a user]
    (swap! a update :users conj (users/create-user-payload user))
    user)
  (create-login! [a id password]
    (swap! a update :logins conj {:id id, :hashed_password password}))
  (register-user! [a user]
    (users/-register-user-impl! a user))
  (update-user [a username update-map]
    (let [idx (first (find-user-index username (:users @a)))]
      (swap! a update-in [:users idx] merge update-map)
      (get-in @a [:users idx])))
  (get-user-by-id [a user-id]
    (first (filter #(= user-id (:id %))
                   (:users (deref a)))))
  (get-user [a username]
    (first (filter #(= username (:username %))
                   (:users (deref a)))))
  (get-password [a user-id]
    (:hashed_password (first (filter #(= user-id (:id %))
                                     (:logins (deref a))))))
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
    (users/-login a credentials)))

(extend-protocol ArticlePersistence
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
    (postgres/get-full-article a article-name))
  (get-resume-info [a]
    (:resume-info (deref a))))

(comment
  (users/register-user! test-user-db
                        {:first_name "Andrew"
                         :last_name "Lai"
                         :email "me@andrewslai.com"
                         :username "Andrew"
                         :avatar (byte-array (map (comp byte int) "Hello world!"))
                         :password "password"})

  @test-user-db
  (users/get-user test-user-db "new-user")
  )
