(ns andrewslai.clj.wedding-routes-test
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.persistence.memory :as memory]
            [andrewslai.clj.protocols.mem :as memp]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [clojure.string :as string]
            [clojure.test :refer [are deftest is testing use-fixtures]]
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


;; TODO: This is relying on the access control list and `wedding/access-rules`
;;        to determine authorization. Should test authorization instead of
;;        relying on that configuration
;;

(def example-fs
  "An in-memory filesystem used for testing"
  {"media" {"afile" (memory/file {:name     "afile"
                                  :content  {:qux :quz}
                                  :metadata {}})
            "adir"  {"anotherfile" (memory/file {:name     "afile"
                                                 :content  {:qux :quz}
                                                 :metadata {}})}}})

(deftest authorized-user-test
  (are [description auth-backend expected]
    (testing description
      (let [in-mem-fs (atom example-fs)]
        (is (match? expected
                    (tu/app-request
                     h/wedding-app
                     {:request-method :get
                      :uri            "/media/"
                      :headers        (tu/auth-header ["wedding"])}
                     {:auth           auth-backend
                      :wedding-storage (sc/classpath-static-content-wrapper
                                        {:loader          (memp/loader (memory/->MemFS in-mem-fs))
                                         :prefer-handler? true})})))))
    "Authorized request returns 200"
    (tu/authorized-backend)
    {:status  200
     :headers {"Cache-Control" sc/no-cache}
     :body    [{:name "afile"} {:name "adir" :type "directory"}]}

    "Unauthorized request returns 401"
    (tu/unauthorized-backend)
    {:status 401}))

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
                       {:loader          (memp/loader (memory/->MemFS in-mem-fs))
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
                         {:loader          (memp/loader (memory/->MemFS in-mem-fs) )
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
