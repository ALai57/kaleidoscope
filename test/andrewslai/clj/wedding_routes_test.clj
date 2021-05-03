(ns andrewslai.clj.wedding-routes-test
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.persistence.s3 :as fs]
            [andrewslai.clj.test-utils :as tu]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [matcher-combinators.test]))

(defn wedding-route
  [components options]
  (tu/http-request :get "/wedding/media" components options))

(defn mock-fs
  [m]
  (reify fs/FileSystem
    (ls [_ path]
      (when-let [dir (get-in m (filter (complement empty?)
                                       (string/split path #"/")))]
        (map (fn [[k entry]]
               (-> entry
                   (assoc :key (str path k))
                   (dissoc :val)))
             dir)))
    (get-file [_ path]
      (if-let [obj (get-in m (filter (complement empty?)
                                     (string/split path #"/")))]
        (assoc obj :key path)))))

(def mock-files
  {"media" {"a.txt" {:size 100
                     :etag "abcdef"
                     :val "SOMETHING"}
            "b.txt" {:size 200
                     :etag "bcdefg"
                     :val "SOMETHING"}}})

(deftest mock-fs-test
  (is (match? [{:key "media/a.txt" :etag "abcdef" :size 100}
               {:key "media/b.txt" :etag "bcdefg" :size 200}]
              (fs/ls (mock-fs mock-files)
                     "media/"))))

(deftest authorized-user-test
  (is (match? {:status 200 :body [{:key "media/a.txt" :etag "abcdef" :size 100}
                                  {:key "media/b.txt" :etag "bcdefg" :size 200}]}
              (wedding-route {:auth (tu/authorized-backend)
                              :wedding-storage (mock-fs mock-files)}
                             {:headers {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}}))))

(deftest unauthorized-user-test
  (is (match? {:status 400 :body #"Unauthorized for role"}
              (wedding-route {:auth (tu/unauthorized-backend)
                              :wedding-storage (mock-fs mock-files)}
                             {:headers {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}
                              :parser identity}))))

(comment
  (wedding-route {:auth (tu/authorized-backend)}
                 {:headers {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}})

  (auth/jwt-body (make-jwt {:hdr ""
                            :body (auth/clj->b64 {:realm_access {:roles ["wedding"]}})
                            :sig ""}))
  )
