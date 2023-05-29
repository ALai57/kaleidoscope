(ns kaleidoscope.http-api.kaleidoscope-test
  "The highest level tests in the project.

  This namespace tests through the HTTP API - testing that all the components
  work together and that the HTTP API behaves according to spec."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [kaleidoscope.api.portfolio :as portfolio]
            [kaleidoscope.http-api.cache-control :as cc]
            [kaleidoscope.http-api.kaleidoscope :as kaleidoscope]
            [kaleidoscope.init.env :as env]
            [kaleidoscope.models.albums :refer [example-album example-album-2]]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.test-main :as tm]
            [kaleidoscope.test-utils :as tu]
            [kaleidoscope.utils.core :as util]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [peridot.multipart :as mp]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn article?
  [x]
  (s/valid? :kaleidoscope.article/article x))

(defn articles?
  [x]
  (s/valid? :kaleidoscope.article/articles x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing HTTP routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest ping-test
  (let [handler (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                      "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                     env/prepare-kaleidoscope
                     kaleidoscope/kaleidoscope-app
                     tu/wrap-clojure-response)]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    {:revision string?}}
                (handler (mock/request :get "/ping"))))))

(deftest home-test
  (let [handler (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                      "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                     (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                     env/prepare-kaleidoscope
                     kaleidoscope/kaleidoscope-app)]
    (is (match? {:status  200
                 :headers {"Content-Type" #"text/html"}
                 :body    "<div>Hello</div>"}
                (handler (mock/request :get "https://andrewslai.com/"))))))

(deftest swagger-test
  (let [handler (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                      "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                     env/prepare-kaleidoscope
                     kaleidoscope/kaleidoscope-app
                     tu/wrap-clojure-response)]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    map?}
                (handler (mock/request :get "/swagger.json"))))))

(deftest admin-routes-test
  (testing "Authenticated and Authorized happy path"
    (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                    "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                    "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                    "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                   (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                   env/prepare-kaleidoscope
                   kaleidoscope/kaleidoscope-app
                   tu/wrap-clojure-response)]
      (is (match? {:status 200 :body {:message "Got to the admin-route!"}}
                  (app (-> (mock/request :get "https://andrewslai.com/admin")
                           (mock/header "Authorization" "Bearer x")))))))
  (testing "Authenticated but not Authorized cannot access"
    (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                    "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                    "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                    "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                   (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                   env/prepare-kaleidoscope
                   kaleidoscope/kaleidoscope-app
                   tu/wrap-clojure-response)]
      (is (match? {:status 401 :body "Not authorized"}
                  (app (-> (mock/request :get "/admin/")
                           (mock/header "Authorization" "Bearer x"))))))))

(deftest access-rule-configuration-test
  (are [description expected request]
    (testing description
      (let [handler (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                          "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                          "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                          "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                         (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                         env/prepare-kaleidoscope
                         kaleidoscope/kaleidoscope-app)]
        (is (match? expected (handler request)))))

    "GET `/ping` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/ping")

    "GET `/` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/")

    "POST `/swagger.json` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/swagger.json")

    "GET `/admin` is not publicly accessible"
    {:status 401} (mock/request :get "https://andrewslai.com/admin")

    "POST `/media/` is not publicly accessible"
    {:status 401} (mock/request :post "/media/")

    "GET `/projects-portfolio` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/projects-portfolio")

    "GET `/compositions` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/compositions")

    "GET `/articles/does-not-exist` is not publicly accessible"
    {:status 401} (mock/request :get "https://andrewslai.com/articles/does-not-exist")

    "PUT `/articles/new-article` is not publicly accessible"
    {:status 401} (mock/request :put "https://andrewslai.com/articles/new-article")))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test of Blogging API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn create-branch
  ([article]
   (create-branch article ""))
  ([article host]
   (-> (mock/request :post (str host "/branches"))
       (mock/json-body article)
       (mock/header "Authorization" "Bearer x"))))

(defn get-branches
  ([]
   (get-branches nil))
  ([query]
   (get-branches query ""))
  ([query host]
   (cond-> (mock/request :get (str host "/branches"))
     true  (mock/header "Authorization" "Bearer x")
     query (mock/query-string query)))
  )

(defn create-version
  ([article-url branch version]
   (create-version "localhost" article-url branch version))
  ([host article-url branch version]
   (-> (mock/request :post (format "%s/articles/%s/branches/%s/versions"
                                   host
                                   article-url
                                   branch))
       (mock/json-body version)
       (mock/header "Authorization" "Bearer x"))))

(defn get-version
  ([branch-id]
   (get-version "localhost" branch-id))
  ([host branch-id]
   (-> (mock/request :get (format "%s/branches/%s/versions" host branch-id))
       (mock/header "Authorization" "Bearer x"))))

(defn get-composition
  [article-url]
  (mock/request :get (format "/compositions/%s" article-url)))

(defn publish-branch
  ([article-url branch-name]
   (publish-branch "localhost" article-url branch-name))
  ([host article-url branch-name]
   (-> (mock/request :put (format "%s/articles/%s/branches/%s/publish"
                                  host
                                  article-url
                                  branch-name))
       (mock/header "Authorization" "Bearer x")))
  )

(defn has-count
  [n]
  (m/pred (fn [x] (= n (count x)))))

(deftest published-article-retrieval-test
  (are [endpoint expected]
    (testing (format "%s returns %s" endpoint expected)
      (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                      "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                     env/prepare-kaleidoscope
                     kaleidoscope/kaleidoscope-app)]
        (is (match? expected
                    (tu/app-request app (mock/request :get (str "http://andrewslai.localhost" endpoint)))))))

    "/compositions"                  {:status 200 :body (has-count 4)}
    "/compositions/my-first-article" {:status 200 :body article?}
    "/compositions/does-not-exist"   {:status 404}
    ))

(deftest create-branch-happy-path-test
  (let [app     (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                      "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                     env/prepare-kaleidoscope
                     kaleidoscope/kaleidoscope-app
                     tu/wrap-clojure-response)
        article {:article-tags "thoughts"
                 :article-url  "my-test-article"
                 :author       "Andrew Lai"}

        create-result          (app (-> article
                                        (assoc :branch-name "branch-1")
                                        (create-branch "http://andrewslai.com")))
        [{:keys [article-id]}] (:body create-result)]

    (testing "Article creation succeeds for branch 1"
      (is (match? {:status 200 :body [{:article-id some?
                                       :branch-id  some?}]}
                  create-result)))

    (testing "Article creation succeeds for branch 2"
      (is (match? {:status 200 :body [{:article-id some?
                                       :branch-id  some?}]}
                  (app (-> article
                           (assoc :branch-name "branch-2"
                                  :article-id  article-id)
                           (create-branch "http://andrewslai.com"))))))

    (testing "The 2 branches were created"
      (is (match? {:status 200 :body (has-count 2)}
                  (app (-> {:article-id article-id}
                           (get-branches "http://andrewslai.com"))))))

    (testing "The branches can't be viewed when asking wrong host"
      (is (match? {:status 401}
                  (app (-> {:article-id article-id}
                           (get-branches "http://other-host.com"))))))))

(deftest publish-branch-test
  (let [app             (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                              "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                              "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                              "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                             (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                             env/prepare-kaleidoscope
                             kaleidoscope/kaleidoscope-app
                             tu/wrap-clojure-response)
        article         {:article-url "my-test-article"}
        branch          {:branch-name "mybranch"}
        version         {:content "<p>Hi</p>"}
        create-response (app (create-version "https://andrewslai.com"
                                             (:article-url article)
                                             (:branch-name branch)
                                             (merge article version)))]

    (testing "Create version works"
      (is (match? {:status 200}
                  create-response)))

    (testing "Cannot retrieve an unpublished article by `/compositions` endpoint"
      (is (match? {:status 404}
                  (app (get-composition (:article-url article))))))

    (testing "Publish article"
      (is (match? {:status 200 :body [(merge article branch)]}
                  (app (publish-branch "https://andrewslai.com"
                                       (:article-url article)
                                       (get-in create-response [:body 0 :branch-name]))))))

    (testing "Can retrieve an published article by `/compositions` endpoint"
      (is (match? {:status 200 :body (merge article branch version {:author "Test User"})}
                  (app (get-composition (:article-url article))))))

    (testing "Cannot commit to published branch"
      (log/with-min-level :fatal
        (is (match? {:status 409 :body "Cannot change a published branch"}
                    (app (create-version "https://andrewslai.com"
                                         (:article-url article)
                                         (:branch-name branch)
                                         (merge article version)))))))))

(deftest get-versions-test
  (let [app       (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                        "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                        "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                        "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                       (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                       env/prepare-kaleidoscope
                       kaleidoscope/kaleidoscope-app
                       tu/wrap-clojure-response)
        article   {:article-tags  "thoughts"
                   :article-title "My test article"
                   :article-url   "my-test-article"}
        version-1 {:content "<p>Hello</p>"}
        version-2 {:content "<p>Hello</p>"}

        {[article-branch] :body :as create-result} (app (create-branch (assoc article :branch-name "branch-1")
                                                                       "https://andrewslai.com"))
        ]

    (testing "Create article branch"
      (is (match? {:status 200 :body [{:article-id some? :branch-id some? :article-title "My test article"}]}
                  create-result)))

    (testing "Commit to branch twice"
      (is (match? {:status 200 :body [(merge version-1 article)]}
                  (app (create-version "https://andrewslai.com"
                                       (:article-url article) "branch-1" version-1))))
      (is (match? {:status 200 :body [(merge version-2 article)]}
                  (app (create-version "https://andrewslai.com"
                                       (:article-url article) "branch-1" version-2))))
      (is (match? {:status 200 :body (has-count 2)}
                  (app (get-version "https://andrewslai.com" (:branch-id article-branch)))))
      (is (match? {:status 401}
                  (app (get-version "https://other-domain.com" (:branch-id article-branch)))))
      )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Resume API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest portfolio-test
  (let [app      (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                       "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                       "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                       "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                      (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                      env/prepare-kaleidoscope
                      kaleidoscope/kaleidoscope-app
                      tu/wrap-clojure-response)
        response (app (mock/request :get "/projects-portfolio"))]
    (is (match? {:status 200 :body portfolio/portfolio?}
                response)
        (s/explain-str :kaleidoscope/portfolio (:body response)))))

(defn make-example-file-upload-request-png
  "A function because the body is an input stream, which is consumable and must
  be regenerated each request"
  ([fname]
   (make-example-file-upload-request-png "localhost" "example-image.png"))
  ([host fname]
   (-> (mock/request :post (format "%s/v2/photos" host))
       (mock/header "Authorization" "Bearer x")
       (util/deep-merge (mp/build {:file (-> (str "public/images/" fname)
                                             io/resource
                                             io/file)
                                   :more     "stuff"})))))

(deftest photos-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)

        create-response (app (make-example-file-upload-request-png "https://andrewslai.com" "example-image.png"))
        get-response    (app (mock/request :get "https://andrewslai.com/v2/photos/" {:filename "mobile.png"}))
        image-id        (get-in get-response [:body 0 :id])]

    (testing "Upload works"
      (is (match? {:status  201
                   :headers {"Content-Type" "application/json"}
                   :body    [{:photo-id string?
                              :version-ids [{:filename "raw.png"}
                                            {:filename "thumbnail.png"}
                                            {:filename "gallery.png"}
                                            {:filename "monitor.png"}
                                            {:filename "mobile.png"}]}]}
                  create-response)))

    (testing "Retrieval works"
      (is (match? {:status 200
                   :body   [{:filename "mobile.png"}]}
                  get-response))
      (is (match? {:status  200
                   :headers {"Content-Type" #"application/json"}
                   :body    [{:id             image-id
                              :path           (str "/v2/photos/" image-id "/raw.png")
                              :image-category "raw"
                              :hostname       "andrewslai.com"}
                             {:path           (str "/v2/photos/" image-id "/thumbnail.png")
                              :image-category "thumbnail"}
                             {:path           (str "/v2/photos/" image-id "/gallery.png")
                              :image-category "gallery"}
                             {:path           (str "/v2/photos/" image-id "/monitor.png")
                              :image-category "monitor"}
                             {:path           (str "/v2/photos/" image-id "/mobile.png")
                              :image-category "mobile"}]}
                  (app (mock/request :get (str "https://andrewslai.com/v2/photos/" image-id)))))
      (is (match? {:status 200}
                  (app (mock/request :get (format "https://andrewslai.com/v2/photos/%s/raw.png" image-id ))))))

    (testing "Etags work"
      (let [image-path (str "media/" image-id "/raw.png")]
        (is (match? {:status 304}
                    (app (-> (mock/request :get (str "https://andrewslai.com/v2/photos/" image-id "/raw.png"))
                             ;; The in-memory filesystem uses the MD5 hash of
                             ;; the path to calculate the version. So if we use that here,
                             ;; we should hit the code path that triggers an ETag match
                             (mock/header "If-None-Match" (fs/md5 image-path))))))))))

(deftest create-and-remove-group-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]

    (testing "No groups to start"
      (is (match? {:status 200
                   :body   empty?}
                  (app (-> (mock/request :get "https://andrewslai.com/groups")
                           (mock/header "Authorization" "Bearer user first-user"))))))

    (let [result (app (-> (mock/request :post "https://andrewslai.com/groups")
                          (mock/header "Authorization" "Bearer user first-user")
                          (mock/json-body {:display-name "my-display-name"})))
          id     (get-in result [:body 0 :id])]
      (testing "Create group"
        (is (match? {:status 200
                     :body   [{:id string?}]}
                    result)))

      (testing "A different user ID creates a second group"
        (is (match? {:status 200
                     :body   [{:id "other-users-group"}]}
                    (app (-> (mock/request :put "https://andrewslai.com/groups/other-users-group")
                             (mock/header "Authorization" "Bearer user second-user")
                             (mock/json-body {:display-name "my-display-name"}))))))

      (testing "I can only retrieve groups I own"
        (let [response (app (-> (mock/request :get "https://andrewslai.com/groups")
                                (mock/header "Authorization" "Bearer user first-user")))]
          (is (match? {:status 200
                       :body   [{:group-id id}]}
                      response))
          (is (= 1 (count (:body response))))))

      (testing "I can only delete groups I own"
        (is (match? {:status 401}
                    (app (-> (mock/request :delete "https://andrewslai.com/groups/other-users-group")
                             (mock/header "Authorization" "Bearer user first-user"))))))

      (testing "Deleted group doesn't exist"
        (is (match? {:status 204}
                    (app (-> (mock/request :delete (format "https://andrewslai.com/groups/%s" id))
                             (mock/header "Authorization" "Bearer user first-user")))))
        (is (= 0 (count (:body (app (-> (mock/request :get "https://andrewslai.com/groups")
                                        (mock/header "Authorization" "Bearer user first-user")))))))))))

(deftest retrieve-group-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)

        result   (app (-> (mock/request :post "https://andrewslai.com/groups")
                          (mock/header "Authorization" "Bearer user first-user")
                          (mock/json-body {:display-name "my-display-name"})))
        group-id (get-in result [:body 0 :id])]

    (testing "Create group"
      (is (match? {:status 200
                   :body   [{:id group-id}]}
                  result)))

    (let [add-member-result (app (-> (mock/request :post (format "https://andrewslai.com/groups/%s/members" group-id))
                                     (mock/header "Authorization" "Bearer user first-user")
                                     (mock/json-body {:email "my-email@email.com"
                                                      :alias "Androo"})))
          member-id         (get-in add-member-result [:body 0 :id])]
      (testing "Add members"
        (is (match? {:status 200
                     :body   [{:id string?}]}
                    add-member-result)))

      (testing "Retrieve group with members"
        (let [response (app (-> (mock/request :get "https://andrewslai.com/groups")
                                (mock/header "Authorization" "Bearer user first-user")))]
          (is (match? {:status 200
                       :body   [{:group-id     group-id
                                 :display-name "my-display-name"
                                 :memberships  [{:membership-id         string?
                                                 :membership-created-at string?
                                                 :alias                 "Androo"
                                                 :email                 "my-email@email.com"}]}]}
                      response))))

      (testing "Remove member from group"
        (let [response (app (-> (mock/request :delete (format "https://andrewslai.com/groups/%s/members/%s" group-id member-id))
                                (mock/header "Authorization" "Bearer user first-user")))]
          (is (match? {:status 204}
                      response))
          (is (match? {:status 200
                       :body   [{:memberships empty?}]}
                      (app (-> (mock/request :get "https://andrewslai.com/groups")
                               (mock/header "Authorization" "Bearer user first-user")))))
          )))))

(deftest index.html-test
  (let [system (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                     "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                     "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                     "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                    (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                    env/prepare-kaleidoscope)
        app    (->> system
                    kaleidoscope/kaleidoscope-app
                    tu/wrap-clojure-response)]
    (is (match? {:status  200
                 :headers {"Cache-Control" cc/revalidate-0s}
                 :body    [{:name "afile"} {:name "adir" :type "directory"}]}
                (app (-> (mock/request :get "https://andrewslai.com/media/")
                         (mock/header "Authorization" "Bearer x")))))))

(deftest albums-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (testing "No albums in DB to start"
      (is (match? {:status 200 :body (comp (partial = 3) count)}
                  (app (mock/request :get "https://andrewslai.com/albums")))))

    (let [{:keys [body] :as result} (app (-> (mock/request :post "https://andrewslai.com/albums")
                                             (mock/json-body example-album)))]

      (testing "Create new album"
        (is (match? {:status 200 :body map?}
                    result)))

      (testing "Retrieve newly created album"
        (is (match? {:status 200 :body (-> example-album
                                           (assoc :modified-at string?
                                                  :created-at  string?)
                                           (update :cover-photo-id str))}
                    (app (mock/request :get (format "https://andrewslai.com/albums/%s" (:id body)))))))

      (testing "Update album"
        (is (match? {:status 200 :body {:id (:id body)}}
                    (-> (mock/request :put (format "https://andrewslai.com/albums/%s" (:id body)))
                        (mock/json-body {:album-name "Updated name"})
                        app)))
        (is (match? {:status 200 :body {:album-name "Updated name"}}
                    (app (mock/request :get (format "https://andrewslai.com/albums/%s" (:id body))))))))))

(defn string-uuid?
  [s]
  (and (string? s)
       (uuid? (java.util.UUID/fromString s))))

(deftest album-contents-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (let [[photo-upload-result] (:body (app (make-example-file-upload-request-png "https://andrewslai.com" "example-image.png")))
          album-create-result   (:body (app (-> (mock/request :post "https://andrewslai.com/albums")
                                                (mock/json-body example-album))))
          photo-id              (:photo-id photo-upload-result)
          album-id              (:id album-create-result)]

      (testing "Album is empty to start"
        (is (match? {:status 200 :body []}
                    (app (-> (mock/request :get (format "https://andrewslai.com/albums/%s/contents" album-id)))))))

      (let [result           (app (-> (mock/request :post (format "https://andrewslai.com/albums/%s/contents" album-id))
                                      (mock/json-body [{:id photo-id}])))
            album-content-id (get-in result [:body 0 :id])]
        (testing "Successfully added photo to album"
          (is (match? {:status 200 :body [{:id string-uuid?}]}
                      result))
          (is (match? {:status 200 :body [{:album-id         album-id
                                           :photo-id         photo-id
                                           :album-content-id album-content-id}]}
                      (app (-> (mock/request :get (format "https://andrewslai.com/albums/%s/contents" album-id))))))
          (is (match? {:status 200 :body {:album-id         album-id
                                          :photo-id         photo-id
                                          :album-content-id album-content-id}}
                      (app (-> (mock/request :get (format "https://andrewslai.com/albums/%s/contents/%s" album-id album-content-id)))))))

        (let [delete-result (app (mock/request :delete (format "https://andrewslai.com/albums/%s/contents/%s" album-id album-content-id)))]
          (testing "Successfully removed photo from album"
            (is (match? {:status 204}
                        delete-result))
            (is (match? {:status 200 :body empty?}
                        (app (mock/request :get (format "https://andrewslai.com/albums/%s/contents" album-id)))))
            (is (match? {:status 404}
                        (app (mock/request :get (format "https://andrewslai.com/albums/%s/contents/%s" album-id album-content-id))))))
          )
        ))))

(deftest contents-retrieval-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]

    ;; Add a photo to two separate albums
    (let [[{photo-1-id :photo-id}] (:body (app (make-example-file-upload-request-png "https://andrewslai.com" "example-image.png")))
          [{photo-2-id :photo-id}] (:body (app (make-example-file-upload-request-png "https://andrewslai.com" "example-image.png")))
          {album-1-id :id}         (:body (app (-> (mock/request :post "https://andrewslai.com/albums")
                                                   (mock/json-body example-album))))
          {album-2-id :id}         (:body (app (-> (mock/request :post "https://andrewslai.com/albums")
                                                   (mock/json-body example-album-2))))]

      (testing "Contents are empty to start"
        (is (match? {:status 200 :body []}
                    (app (-> (mock/request :get "https://andrewslai.com/albums/-/contents"))))))

      (app (-> (mock/request :post (format "https://andrewslai.com/albums/%s/contents" album-1-id))
               (mock/json-body [{:id photo-1-id}
                                {:id photo-2-id}])))
      (app (-> (mock/request :post (format "https://andrewslai.com/albums/%s/contents" album-2-id))
               (mock/json-body [{:id photo-1-id}])))

      (testing "Contents retrieved from multiple albums"
        (is (match? {:status 200 :body [{:album-name (:album-name example-album)
                                         :photo-id   photo-1-id}
                                        {:album-name (:album-name example-album)
                                         :photo-id   photo-2-id}
                                        {:album-name (:album-name example-album-2)
                                         :photo-id   photo-1-id}]}
                    (app (-> (mock/request :get "https://andrewslai.com/albums/-/contents"))))) ))))

(deftest albums-auth-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app)]
    (testing "Default access rules restrict access"
      (is (match? {:status 401}
                  (app (mock/request :get "https://andrewslai.com/albums")))))))

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
