(ns andrewslai.clj.wedding-routes-test
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.protocols.mem :as memp]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]
            [clj-http.client :as http]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(defn wedding-route
  [components options]
  (tu/http-request :get "/media/" components (assoc options
                                                    :app h/wedding-app)))

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

;; TODO: This is relying on the access control list and `wedding/access-rules`
;;        to determine authorization. Should test authorization instead of
;;        relying on that configuration
(deftest authorized-user-test
  (let [in-mem-fs (atom {"mem:/media/" :HELLO})]
    (is (match? {:status 200
                 :headers {"Cache-Control" sc/no-cache}
                 :body :HELLO}
                (wedding-route
                 {:auth (tu/authorized-backend)
                  :wedding-storage
                  (sc/classpath-static-content-wrapper
                   {:loader          (memp/loader (memp/stream-handler in-mem-fs))
                    :prefer-handler? true})}
                 {:headers {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}
                  :parser identity})))))

(deftest unauthorized-user-test
  (let [in-mem-fs (atom {"mem:/media/" :HELLO})]
    (is (match? {:status 401}
                (wedding-route
                 {:auth (tu/unauthorized-backend)
                  :wedding-storage
                  (sc/classpath-static-content-wrapper
                   {:loader          (memp/loader (memp/stream-handler in-mem-fs))
                    :prefer-handler? true})}
                 {:headers {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}
                  :parser  identity})))))


(comment
  (http/put "http://caheriaguilar.and.andrewslai.com.localhost:5000/media/something"
            {:multipart [{:name "title" :content "Something good"}
                         {:name "Content/type" :content "image/jpeg"}
                         {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                         {:name "lock.svg" :content (clojure.java.io/input-stream (clojure.java.io/resource "public/images/lock.svg"))}]})
  )


;; Create an object that reifies the filesystem protocol to use with the loader
(deftest multipart-upload-test
  (let [tmpdir    (tu/mktmpdir "andrewslai-test")
        tmpfile   (tu/mktmp "multipart.txt" tmpdir)
        in-mem-fs (atom {})
        path      (str (.getName tmpdir) "/" (.getName tmpfile))

        wedding-app (h/wedding-app
                     {:auth (tu/authorized-backend)
                      :wedding-storage
                      (sc/classpath-static-content-wrapper
                       {:loader          (memp/loader (memp/stream-handler in-mem-fs))
                        :prefer-handler? true})})]
    #_(is (match? {:status  200
                   :headers {"Cache-Control" sc/no-cache}
                   :body    :HELLO}
                  ))
    (wedding-app
     {:headers        {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}
      :request-method :put
      :params         {"title"        "Something good"
                       "Content-Type" "image/jpeg"
                       "eggplan"      "Eggplants"
                       "file.txt"     {:filename     "lock.svg"
                                       :content-type "image/svg"
                                       :tempfile     (clojure.java.io/input-stream (clojure.java.io/resource "public/images/lock.svg"))
                                       :size         1034}}
      :parser         identity})

    )

  )



(defn tmpfile
  ([]
   (tmpfile "andrewslai-test" "test.txt"))
  ([fname]
   (tmpfile "andrewslai-test" fname))
  ([dir fname]
   (let [tmpdir    (tu/mktmpdir dir)
         tmpfile   (tu/mktmp fname tmpdir)]
     (str (.getAbsolutePath tmpdir) "/" (.getName tmpfile)))))


(-> (let [path      (tmpfile "multipart.txt")
          in-mem-fs (atom {"mem:/media/" :HELLO})

          request {"title"        "Something good"
                   "Content-Type" "image/jpeg"
                   "eggplan"      "Eggplants"
                   "file.txt"     {:filename     "lock.svg"
                                   :content-type "image/svg"
                                   :tempfile     (clojure.java.io/input-stream (clojure.java.io/file path))
                                   :size         1034}}

          wedding-app (h/wedding-app
                       {:auth        (tu/authorized-backend)
                        :persistence ()
                        :wedding-storage
                        (sc/classpath-static-content-wrapper
                         {:loader          (memp/loader (memp/stream-handler in-mem-fs))
                          :prefer-handler? true})})]
      #_(is (match? {:status  200
                     :headers {"Cache-Control" sc/no-cache}
                     :body    :HELLO}
                    ))
      (wedding-app
       {:headers        {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}
        :request-method :put
        :uri            "/media/something"
        :params         {"title"        "Something good"
                         "Content-Type" "image/jpeg"
                         "eggplan"      "Eggplants"
                         "file.txt"     {:filename     "lock.svg"
                                         :content-type "image/svg"
                                         :tempfile     (clojure.java.io/input-stream (clojure.java.io/resource "public/images/lock.svg"))
                                         :size         1034}}
        :parser         identity})

      )
    #_#_:body
    slurp
    )


















(comment
  (wedding-route {:auth (tu/authorized-backend)}
                 {:headers {"Authorization" (tu/bearer-token {:realm_access {:roles ["wedding"]}})}})

  (auth/jwt-body (make-jwt {:hdr ""
                            :body (auth/clj->b64 {:realm_access {:roles ["wedding"]}})
                            :sig ""}))
  )
