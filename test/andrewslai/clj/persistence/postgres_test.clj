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
  postgres/RelationalDatabase
  (delete! [a table where]
    (let [k (first (keys where))
          v (where k)
          updated (remove #(= v (k %)) ((keyword table) @a))]
      (swap! a assoc (keyword table) updated)
      [1]))
  (select [a table where]
    (let [k (first (keys where))
          v (where k)]
      (if (empty? where)
        ((keyword table) (deref a))
        (filter #(= v (k %)) ((keyword table) (deref a))))))
  (update! [a table payload where]
    (let [idx (first (find-index where (:users @a)))]
      (swap! a update-in [:users idx] merge payload)
      [1]))
  (insert! [a table payload]
    (swap! a update (keyword table) conj payload)))

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
