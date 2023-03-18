(ns kaleidoscope.clj.http-api.wedding-test
  (:require [kaleidoscope.clj.http-api.cache-control :as cc]
            [kaleidoscope.clj.http-api.wedding :as wedding]
            [kaleidoscope.clj.init.env :as env]
            [kaleidoscope.clj.test-main :as tm]
            [kaleidoscope.clj.test-utils :as tu]
            [kaleidoscope.clj.utils.core :as util]
            [kaleidoscope.cljc.specs.albums :refer [example-album example-album-2]]
            [clj-http.client :as http]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures and setup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(defn make-example-file-upload-request
  "A function because the body is an input stream, which is consumable and must
  be regenerated each request"
  ([]
   (make-example-file-upload-request "lock.svg"))
  ([fname]
   (util/deep-merge {:headers        (tu/auth-header ["wedding"])
                     :request-method :post
                     :uri            "/media/"}
                    (tu/assemble-multipart "my boundary here"
                                           [{:part-name    "file-contents"
                                             :file-name    fname
                                             :content-type "image/svg+xml"
                                             :content      (-> "public/images/lock.svg"
                                                               clojure.java.io/resource
                                                               slurp)}]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Access rules
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(deftest access-rules-test
    (are [description expected configuration]
      (testing description
        (let [handler (->> configuration
                           (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                           env/prepare-wedding
                           wedding/wedding-app)]
          (is (match? expected (handler (-> (mock/request :get "/ping")
                                            (mock/header "Authorization" "Bearer x")))))))

      "Public-access routes can be reached by unauthenticated user"
      {:status 200} {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                     "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                     "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
                     "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "none"}

      "Restricted-access routes can be reached by authorized user"
      {:status 200} {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                     "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "custom-authenticated-user"
                     "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
                     "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "none"}

      #_#_#_"Restricted-access routes cannot be reached by unauthorized user"
      {:status 401} {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                     "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                     "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
                     "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}

      #_#_#_"Restricted-access routes cannot be reached by unauthenticated user"
      {:status 401} {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                     "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                     "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
                     "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}
      ))

(deftest access-rule-configuration-test
  (are [description expected request]
    (testing description
      (let [handler (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                          "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                          "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
                          "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}
                         (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                         env/prepare-wedding
                         wedding/wedding-app)]
        (is (match? expected (handler request)))))

    "GET `/ping` is publicly accessible"
    {:status 200} (mock/request :get "/ping")

    "GET `/media/` is not publicly accessible"
    {:status 401} (mock/request :get "/media/")

    "POST `/media/` is not publicly accessible"
    {:status 401} (mock/request :post "/media/")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP API test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest static-content-test
  (let [system (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                     "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "custom-authenticated-user"
                     "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
                     "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}
                    (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                    env/prepare-wedding)
        app    (->> system
                    wedding/wedding-app
                    tu/wrap-clojure-response)]
    (is (match? {:status  200
                 :headers {"Cache-Control" cc/revalidate-0s}
                 :body    [{:name "afile"} {:name "adir" :type "directory"}]}
                (app {:request-method :get
                      :headers        {"Authorization" "Bearer x"}
                      :uri            "/media/"})))))

(deftest upload-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                  "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                 env/prepare-wedding
                 wedding/wedding-app)]

    (is (match? {:status 201}
                (app (make-example-file-upload-request))))

    (is (match? {:status  200
                 :headers {"Content-Type"  "image/svg+xml"
                           "Cache-Control" cc/cache-30d}}
                (app (mock/request :get "/media/lock.svg"))))))

(deftest albums-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                  "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "none"}
                 (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                 env/prepare-wedding
                 wedding/wedding-app
                 tu/wrap-clojure-response)]
    (testing "No albums in DB to start"
      (is (match? {:status 200 :body (comp (partial = 3) count)}
                  (app (mock/request :get "/albums")))))

    (let [{:keys [body] :as result} (app (-> (mock/request :post "/albums")
                                             (mock/json-body example-album)))]

      (testing "Create new album"
        (is (match? {:status 200 :body map?}
                    result)))

      (testing "Retrieve newly created album"
        (is (match? {:status 200 :body (-> example-album
                                           (assoc :modified-at string?
                                                  :created-at  string?)
                                           (update :cover-photo-id str))}
                    (app (mock/request :get (format "/albums/%s" (:id body)))))))

      (testing "Update album"
        (is (match? {:status 200 :body {:id (:id body)}}
                    (-> (mock/request :put (format "/albums/%s" (:id body)))
                        (mock/json-body {:album-name "Updated name"})
                        app)))
        (is (match? {:status 200 :body {:album-name "Updated name"}}
                    (app (mock/request :get (format "/albums/%s" (:id body))))))))))

(defn string-uuid?
  [s]
  (and (string? s)
       (uuid? (java.util.UUID/fromString s))))

(deftest album-contents-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                  "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                 env/prepare-wedding
                 wedding/wedding-app
                 tu/wrap-clojure-response)]
    (let [photo-upload-result (:body (app (make-example-file-upload-request)))
          album-create-result (:body (app (-> (mock/request :post "/albums")
                                              (mock/json-body example-album))))
          photo-id            (:id photo-upload-result)
          album-id            (:id album-create-result)]

      (testing "Album is empty to start"
        (is (match? {:status 200 :body []}
                    (app (-> (mock/request :get (format "/albums/%s/contents" album-id)))))))

      (let [result           (app (-> (mock/request :post (format "/albums/%s/contents" album-id))
                                      (mock/json-body [{:id photo-id}])))
            album-content-id (get-in result [:body 0 :id])]
        (testing "Successfully added photo to album"
          (is (match? {:status 200 :body [{:id string-uuid?}]}
                      result))
          (is (match? {:status 200 :body [{:album-id         album-id
                                           :photo-id         photo-id
                                           :album-content-id album-content-id}]}
                      (app (-> (mock/request :get (format "/albums/%s/contents" album-id))))))
          (is (match? {:status 200 :body {:album-id         album-id
                                          :photo-id         photo-id
                                          :album-content-id album-content-id}}
                      (app (-> (mock/request :get (format "/albums/%s/contents/%s" album-id album-content-id)))))))

        (let [delete-result (app (mock/request :delete (format "/albums/%s/contents/%s" album-id album-content-id)))]
          (testing "Successfully removed photo from album"
            (is (match? {:status 204}
                        delete-result))
            (is (match? {:status 200 :body empty?}
                        (app (mock/request :get (format "/albums/%s/contents" album-id)))))
            (is (match? {:status 404}
                        (app (mock/request :get (format "/albums/%s/contents/%s" album-id album-content-id))))))
          )
        ))))

(deftest contents-retrieval-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                  "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                 env/prepare-wedding
                 wedding/wedding-app
                 tu/wrap-clojure-response)]

    ;; Add a photo to two separate albums
    (let [{photo-1-id :id} (:body (app (make-example-file-upload-request "foo.svg")))
          {photo-2-id :id} (:body (app (make-example-file-upload-request "bar.svg")))
          {album-1-id :id} (:body (app (-> (mock/request :post "/albums")
                                           (mock/json-body example-album))))
          {album-2-id :id} (:body (app (-> (mock/request :post "/albums")
                                           (mock/json-body example-album-2))))]

      (testing "Contents are empty to start"
        (is (match? {:status 200 :body []}
                    (app (-> (mock/request :get "/albums/-/contents"))))))

      (app (-> (mock/request :post (format "/albums/%s/contents" album-1-id))
               (mock/json-body [{:id photo-1-id}
                                {:id photo-2-id}])))
      (app (-> (mock/request :post (format "/albums/%s/contents" album-2-id))
               (mock/json-body [{:id photo-1-id}])))

      (testing "Contents retrieved from multiple albums"
        (is (match? {:status 200 :body [{:album-name (:album-name example-album)
                                         :photo-id   photo-1-id}
                                        {:album-name (:album-name example-album)
                                         :photo-id   photo-2-id}
                                        {:album-name (:album-name example-album-2)
                                         :photo-id   photo-1-id}]}
                    (app (-> (mock/request :get "/albums/-/contents"))))) ))))

(deftest albums-auth-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                  "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/WEDDING-BOOT-INSTRUCTIONS)
                 env/prepare-wedding
                 wedding/wedding-app)]
    (testing "Default access rules restrict access"
      (is (match? {:status 401}
                  (app (mock/request :get "/albums")))))))

(comment
  (require '[clj-http.client :as http])

  (println (:body (tu/assemble-multipart "OZqYohSB93zIWImnnfy2ekkaK8I_BDbVmtiTi"
                                         [{:part-name    "file-contents"
                                           :file-name    "lock.svg"
                                           :content-type "image/svg+xml"
                                           :content      (-> "public/images/lock.svg"
                                                             clojure.java.io/resource
                                                             slurp)}
                                          {:part-name    "file-contents"
                                           :file-name    "lock.svg"
                                           :content-type "image/svg+xml"
                                           :content      (-> "public/images/lock.svg"
                                                             clojure.java.io/resource
                                                             slurp)}])))

  ;; 2021-09-04: This is working - need to make sure the actual index.html can
  ;; make the same request
  (http/request {:scheme      "http"
                 :server-name "caheriaguilar.and.andrewslai.com.localhost"
                 :server-port "5000"
                 :method      :put
                 :uri         "/media/lock.svg"
                 :multipart   [{:name "name" :content "lock.svg"}
                               {:name "content-type" :content "image/svg+xml"}
                               {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                               {:name    "lock.svg"
                                :content (-> "public/images/lock.svg"
                                             clojure.java.io/resource
                                             clojure.java.io/input-stream)}]})



  )