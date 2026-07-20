(ns kaleidoscope.http-api.kaleidoscope-test
  "The highest level tests in the project.

  This namespace tests through the HTTP API - testing that all the components
  work together and that the HTTP API behaves according to spec."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [kaleidoscope.api.articles :as articles-api]
            [kaleidoscope.api.themes :as themes-api]
            [kaleidoscope.api.portfolio :as portfolio]
            [kaleidoscope.http-api.auth.buddy-backends :as bb]
            [kaleidoscope.http-api.cache-control :as cc]
            [kaleidoscope.http-api.kaleidoscope :as kaleidoscope]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.init.env :as env]
            [kaleidoscope.models.albums :refer [example-album example-album-2]]
            [kaleidoscope.models.articles :as models.articles]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as in-mem]
            [kaleidoscope.test-main :as tm]
            [kaleidoscope.test-utils :as tu]
            [kaleidoscope.utils.core :as util]
            [malli.core :as malli]
            [malli.transform :as mt]
            [next.jdbc :as jdbc]
            [matcher-combinators.core :as mcc]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.result :as result]
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
(defn- malli-match
  "Expects `actual` to be JSON - so it must be interpreted/decoded
  and then compared to the expected schema."
  [schema actual]
  (let [clojurized (malli/decode schema actual (mt/json-transformer))
        mismatch   (malli/explain schema clojurized)]
    (cond
      mismatch {::result/type   :mismatch
                ::result/value  mismatch
                ::result/weight 1}
      :else    {::result/type   :match
                ::result/value  actual
                ::result/weight 0})))

(defn malli-matcher
  "Create a matcher that matches against a Malli schema.

  On failure, uses the Malli explanation to show why failure happened"
  [schema]
  (reify mcc/Matcher
    (-matcher-for [this] schema)
    (-matcher-for [this _] schema)
    (-match [this actual]
      (malli-match schema actual))))

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
                 :body    {:version string?
                           :revision string?}}
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

(deftest static-chrome-serves-from-the-shared-client-store-test
  ;; /static/* and /favicon.ico serve from kaleidoscope.client for every tenant —
  ;; not the per-tenant asset-store. Give the client store a marker the tenant
  ;; store lacks; if the request 200s, it was served from the shared store.
  (let [system   (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS
                                     {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                                      "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "public-access"
                                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"})
        adapters (:kaleidoscope-static-content-adapters system)
        mkfile   (fn [name s] (in-mem/file {:name name :version "1"
                                            :content (java.io.ByteArrayInputStream. (.getBytes (str s)))}))]
    ;; client store has the marker + favicon; the per-tenant store is empty
    (reset! (:store (get adapters "kaleidoscope.client"))
            {"static" {"marker.txt"  (mkfile "marker.txt" "CLIENT")
                       "favicon.ico" (mkfile "favicon.ico" "FAV")}})
    (reset! (:store (get adapters "andrewslai.com")) {})
    (let [app (->> system env/prepare-kaleidoscope kaleidoscope/kaleidoscope-app tu/wrap-clojure-response)]
      (testing "/static/* is served from kaleidoscope.client though the tenant store is empty"
        (is (= 200 (:status (app (mock/request :get "https://andrewslai.com/static/marker.txt"))))))
      (testing "/favicon.ico is served from kaleidoscope.client (static/favicon.ico)"
        (is (= 200 (:status (app (mock/request :get "https://andrewslai.com/favicon.ico")))))))))

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
                (handler  (mock/request :get "/openapi.json"))))))

(deftest tenant-resolver-boots-test
  (let [c (->> {"KALEIDOSCOPE_DB_TYPE" "embedded-h2" "KALEIDOSCOPE_AUTH_TYPE" "always-unauthenticated"
                "KALEIDOSCOPE_AUTHORIZATION_TYPE" "use-access-control-list" "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"
                "KALEIDOSCOPE_TENANT_RESOLVER_TYPE" "fixed" "KALEIDOSCOPE_TENANT" "andrewslai.com"
                "KALEIDOSCOPE_TENANT_ASSET_BUCKET" "kal-ephemeral"}
               (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS) env/prepare-kaleidoscope)]
    (is (match? {:hostname "andrewslai.com" :asset-store "ephemeral-tenant-assets"}
                ((:tenant-resolver c) {:headers {"host" "kal-eph-xyz.fly.dev"}})))))

(deftest tenant-resolver-default-host-test
  (let [c (->> {"KALEIDOSCOPE_DB_TYPE" "embedded-h2" "KALEIDOSCOPE_AUTH_TYPE" "always-unauthenticated"
                "KALEIDOSCOPE_AUTHORIZATION_TYPE" "use-access-control-list" "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
               (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS) env/prepare-kaleidoscope)]
    (is (match? {:hostname "andrewslai.com" :asset-store "andrewslai.com"}
                ((:tenant-resolver c) {:headers {"host" "andrewslai.com"}})))))

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
                  (app (-> (mock/request :get "/admin")
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
    {:status 200} (mock/request :get "https://andrewslai.com/openapi.json")

    "GET `/admin` is not publicly accessible"
    {:status 401} (mock/request :get "https://andrewslai.com/admin")

    "GET `/projects-portfolio` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/projects-portfolio")

    "GET `/compositions` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/compositions")

    "GET `/articles/does-not-exist` is not publicly accessible"
    {:status 401} (mock/request :get "https://andrewslai.com/articles/does-not-exist")

    "POST `/articles/new-article` is not publicly accessible"
    {:status 401} (mock/request :post "https://andrewslai.com/articles/new-article/branches/new-branch/versions")

    ;; GET /v2/photos/:photo-id is public while GET /v2/photos (the list
    ;; endpoint) requires a writer role. buddy-auth's :pattern matcher uses
    ;; re-matches (whole-string match), so the unanchored `^/v2/photos` rule
    ;; only ever matches the literal "/v2/photos" and doesn't shadow the
    ;; `^/v2/photos/.*` rule below it, despite appearing first in the list.
    "GET `/v2/photos` (list) is not publicly accessible"
    {:status 401} (mock/request :get "https://andrewslai.com/v2/photos")

    "GET `/v2/photos/:photo-id` (individual photo) is publicly accessible"
    {:status 404} (mock/request :get (str "https://andrewslai.com/v2/photos/" (random-uuid)))))

(deftest access-control-list-fails-closed-test
  ;; Critical finding, 2026-07-03 (see PLAN.md): buddy-auth's wrap-access-
  ;; rules defaults :policy to :allow — before this was fixed, a URI that
  ;; didn't match ANY pattern in KALEIDOSCOPE-ACCESS-CONTROL-LIST was served
  ;; with zero authorization enforcement, silently and totally, regardless
  ;; of authentication or role. A single missed or mistyped pattern for any
  ;; future route was a full, invisible data exposure with no error and
  ;; nothing else to catch it.
  ;;
  ;; This can't be tested by hitting kaleidoscope-app with a made-up path,
  ;; because a genuinely unmounted route never reaches the auth middleware
  ;; at all — reitit's own not-found handler answers first, bypassing the
  ;; whole middleware stack (see the comment on that handler in
  ;; kaleidoscope.clj). It has to be tested at the level directly below the
  ;; router: build the real production auth-stack with the real production
  ;; access-control list, and confirm a URI that matches none of its
  ;; patterns is rejected, not allowed through.
  (let [rules   kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST
        stack   (mw/auth-stack (bb/authenticated-backend {:realm_access {:roles []}}) rules)
        handler (reduce (fn [h a-mw] (a-mw h))
                        (fn [_req] {:status 200 :body "should never be reached"})
                        (reverse stack))]
    (testing "A URI matching no pattern in the real ACL is rejected, not allowed"
      (is (= 401 (:status (handler {:uri            "/some-brand-new-route-nobody-registered-in-the-acl"
                                    :request-method :get
                                    :headers        {}})))))))

(deftest api-v1-acl-twin-coverage-test
  ;; Every API resource rule must have an /api/v1 twin present in the real ACL.
  ;; The twins are derived (not hand-written), so this is a guard against a
  ;; future edit that adds a root resource rule but forgets its twin — which
  ;; would 401 that route under /api/v1 (fail-closed, but broken).
  (let [present (set (map (juxt (comp str :pattern) :request-method :handler)
                          kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST))]
    (doseq [rule kaleidoscope/api-resource-access-rules]
      (let [twin (kaleidoscope/with-api-v1-prefix rule)]
        (is (contains? present ((juxt (comp str :pattern) :request-method :handler) twin))
            (str "missing /api/v1 twin for " (:pattern rule)))))))

(deftest api-v1-acl-authorizes-like-root-test
  ;; Tested at the auth-stack level (below the router, like the fails-closed
  ;; test) so it doesn't depend on Task 2's route mounting.
  (let [rules   kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST
        stack   (mw/auth-stack (bb/authenticated-backend {:realm_access {:roles []}}) rules)
        handler (reduce (fn [h a-mw] (a-mw h))
                        (fn [_req] {:status 200 :body "reached"})
                        (reverse stack))
        req     (fn [method uri] {:uri uri :request-method method :headers {}})]
    (testing "GET /api/v1/recipes is public, like GET /recipes"
      (is (= 200 (:status (handler (req :get "/api/v1/recipes"))))))
    (testing "POST /api/v1/recipes requires a writer, like POST /recipes"
      (is (= 401 (:status (handler (req :post "/api/v1/recipes"))))))
    (testing "a root resource path is unchanged"
      (is (= 200 (:status (handler (req :get "/recipes")))))
      (is (= 401 (:status (handler (req :post "/recipes"))))))))

(deftest api-v1-dual-mount-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (testing "GET /api/v1/recipes reaches the same public handler as GET /recipes"
      (is (match? {:status 200 :body sequential?}
                  (app (mock/request :get "https://andrewslai.com/recipes"))))
      (is (match? {:status 200 :body sequential?}
                  (app (mock/request :get "https://andrewslai.com/api/v1/recipes")))))
    (testing "GET /api/v1/projects-portfolio is public, like the root path"
      (is (match? {:status 200}
                  (app (mock/request :get "https://andrewslai.com/api/v1/projects-portfolio")))))
    (testing "POST /api/v1/recipes still requires a writer (401 unauthenticated)"
      (is (match? {:status 401}
                  (app (-> (mock/request :post "https://andrewslai.com/api/v1/recipes")
                           (mock/json-body {}))))))))

(deftest auth-runs-before-coercion-test
  ;; 2026-07-04 production incident: POST /workflows requires a :name field
  ;; in its body. An unauthenticated request with no/invalid body was being
  ;; rejected by Malli coercion (400) before it ever reached the access-rules
  ;; check that should return 401 - coercion ran before auth in the
  ;; middleware chain. Any protected write route with a required body field
  ;; had the same hole. Fixed by moving the auth stack before
  ;; `coercion-middleware` in `kaleidoscope-app`.
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (testing "An unauthenticated request with a body that fails schema validation still gets 401, not 400"
      (is (match? {:status 401}
                  (app (-> (mock/request :post "https://andrewslai.com/workflows")
                           (mock/json-body {}))))))))

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
     query (mock/query-string query))))

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
       (mock/header "Authorization" "Bearer x"))))

(defn has-count
  [n]
  (m/pred (fn [x] (= n (count x)))))

(deftest articles-routes-test
  (are [endpoint expected]
    (testing (format "%s returns %s" endpoint expected)
      (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                      "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                     env/prepare-kaleidoscope
                     kaleidoscope/kaleidoscope-app
                     tu/wrap-clojure-response)]
        (is (match? expected
                    (-> (mock/request :get (str "http://andrewslai.com" endpoint))
                        (mock/header "Authorization" "Bearer x")
                        app)))))

    "/articles"                  {:status 200 :body (has-count 4)}
    "/articles/my-first-article" {:status 200 :body (malli-matcher models.articles/GetArticleResponse)}
    "/articles/does-not-exist"   {:status 404}
    ))

(deftest published-article-retrieval-test
  (are [endpoint expected]
    (testing (format "%s returns %s" endpoint expected)
      (let [components (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                             "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                             "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                             "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                            (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                            env/prepare-kaleidoscope)
            ;; Fixture articles are private by default; mark them public so an
            ;; anonymous request can see them via /compositions.
            _          (doseq [id [1 2 3 4]]
                         (articles-api/update-article! (:database components)
                                                       {:id id :public-visibility true}))
            app        (->> components
                            kaleidoscope/kaleidoscope-app
                            tu/wrap-clojure-response)]
        (is (match? expected
                    (app (mock/request :get (str "http://andrewslai.com" endpoint)))))))

    "/compositions"                  {:status 200 :body (has-count 4)}
    "/compositions/my-first-article" {:status 200 :body (malli-matcher models.articles/GetCompositionResponse)}
    "/compositions/does-not-exist"   {:status 404}))

(deftest fixed-resolver-scopes-content-test
  (testing "Under the fixed tenant resolver, a fly.dev host still serves the pinned tenant's content"
    (let [components (->> {"KALEIDOSCOPE_DB_TYPE"               "embedded-h2"
                           "KALEIDOSCOPE_AUTH_TYPE"             "always-unauthenticated"
                           "KALEIDOSCOPE_AUTHORIZATION_TYPE"    "use-access-control-list"
                           "KALEIDOSCOPE_STATIC_CONTENT_TYPE"   "none"
                           "KALEIDOSCOPE_TENANT_RESOLVER_TYPE"  "fixed"
                           "KALEIDOSCOPE_TENANT"                "andrewslai.com"}
                          (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                          env/prepare-kaleidoscope)
          ;; Fixture article #1 ("my-first-article", hostname "andrewslai.com") already has a
          ;; published branch/version; mark it public so an anonymous request can see it via
          ;; /compositions - same shape as `published-article-retrieval-test` above.
          _          (articles-api/update-article! (:database components)
                                                    {:id 1 :public-visibility true})
          app        (->> components
                          kaleidoscope/kaleidoscope-app
                          tu/wrap-clojure-response)]
      (is (match? {:status 200 :body (m/embeds [{:article-url "my-first-article"}])}
                  (app (mock/request :get "https://kal-eph-xyz.fly.dev/compositions")))))))

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
                                        (create-branch "http://andrewslai.test")))
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
                           (create-branch "http://andrewslai.test"))))))

    (testing "The 2 branches were created"
      (is (match? {:status 200 :body (has-count 2)}
                  (app (-> {:article-id article-id}
                           (get-branches "http://andrewslai.test"))))))

    (testing "The branches can't be viewed when asking wrong host"
      (is (match? {:status 401}
                  (app (-> {:article-id article-id}
                           (get-branches "http://other-host.com"))))))))

(deftest branches-with-null-columns-serialize-test
  (testing "GET /branches serializes legacy rows whose article-title/created-at/modified-at are NULL"
    (let [components (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                           "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                           "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                           "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                          (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                          env/prepare-kaleidoscope)
          db         (:database components)
          app        (->> components
                          kaleidoscope/kaleidoscope-app
                          tu/wrap-clojure-response)
          {:keys [article-id branch-id]} (-> {:article-tags "thoughts"
                                              :article-url  "null-cols-article"
                                              :author       "Andrew Lai"
                                              :branch-name  "main"}
                                             (create-branch "http://andrewslai.test")
                                             app :body first)]
      ;; Reproduce legacy prod data: NULL the nullable branch timestamps and
      ;; the article title (all created non-null by the API on the line above).
      (jdbc/execute! db ["UPDATE article_branches SET created_at = NULL, modified_at = NULL WHERE id = ?" branch-id])
      (jdbc/execute! db ["UPDATE articles SET article_title = NULL WHERE id = ?" article-id])

      (testing "the row round-trips as 200 (response coercion tolerates the NULLs)"
        (is (match? {:status 200
                     :body   (m/embeds [{:branch-id     branch-id
                                         :article-title nil
                                         :created-at    nil
                                         :modified-at   nil}])}
                    (app (get-branches {:article-id article-id} "http://andrewslai.test"))))))))

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

    (testing "Cannot retrieve an published article by `/compositions` endpoint if it isn't public"
      (is (match? {:status 404}
                  (app (-> (mock/request :get (format "https://andrewslai.com/compositions/%s" (:article-url article)))
                           (mock/header "Authorization" "Bearer user first-user"))))))

    (testing "Successfully add user `test@test.com` to allowed user list on article"
      (let [result     (app (-> (mock/request :post "https://andrewslai.com/groups")
                                (mock/header "Authorization" "Bearer user first-user")
                                (mock/json-body {:display-name "my-display-name"})))
            group-id   (get-in result [:body 0 :id])
            article-id (get-in create-response [:body 0 :article-id])]
        (app (-> (mock/request :post (format "https://andrewslai.com/groups/%s/members" group-id))
                 (mock/header "Authorization" "Bearer user first-user")
                 (mock/json-body {:email "first-user@test.com"
                                  :alias "Androo"})))
        (is (match? {:status 200
                     :body   [{:group-id   group-id
                               :article-id article-id}]}
                    (app (-> (mock/request :put "https://andrewslai.com/article-audiences")
                             (mock/json-body {:group-id   group-id
                                              :article-id article-id})
                             (mock/header "Authorization" "Bearer x")))))))

    (testing "Can retrieve an published article by `/compositions` endpoint when authenticated"
      (is (match? {:status 200 :body (merge article branch version {:author "Test User"})}
                  (app (-> (mock/request :get (format "https://andrewslai.com/compositions/%s" (:article-url article)))
                           (mock/header "Authorization" "Bearer user first-user"))))))

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
                                                                       "https://andrewslai.com"))]

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
                  (app (get-version "https://other-domain.com" (:branch-id article-branch))))))))

(deftest themes-get-does-not-leak-across-tenants-test
  ;; GET /themes is public-access; before hostname scoping the handler queried
  ;; get-themes with the raw query params (no hostname), so an anonymous
  ;; visitor to andrewslai.com could enumerate another tenant's themes.
  (let [components (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                         "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                         "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                         "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                        (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                        env/prepare-kaleidoscope)
        _   (themes-api/create-theme! (:database components)
                                      {:display-name "caheri-theme"
                                       :owner-id     "caheri"
                                       :hostname     "caheriaguilar.com"
                                       :config       {:primary {:main "#000"}}})
        app (->> components
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]

    (testing "andrewslai.com cannot see caheriaguilar.com's themes"
      (is (match? {:status 404}
                  (app (mock/request :get "https://andrewslai.com/themes")))))

    (testing "caheriaguilar.com can see its own theme"
      (is (match? {:status 200 :body (m/embeds [{:display-name "caheri-theme"}])}
                  (app (mock/request :get "https://caheriaguilar.com/themes")))))))

(deftest get-versions-does-not-leak-across-tenants-test
  ;; The wrong-host case above is stopped by access control (401). This is
  ;; the subtler leak the tenant scoping closes: an authorized writer on
  ;; andrewslai.com must not be able to read ANOTHER live tenant's versions
  ;; by supplying that tenant's branch-id — the handler used to query
  ;; get-versions by a bare branch-id with no hostname, returning whoever
  ;; owned it.
  (let [components (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                         "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                         "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                         "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                        (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                        env/prepare-kaleidoscope)
        ;; A branch + version owned by a DIFFERENT tenant, seeded straight
        ;; into the DB (caheriaguilar.com is not the request's host).
        [{other-branch-id :branch-id}] (articles-api/new-version! (:database components)
                                                                  {:article-url "caheri-secret"
                                                                   :hostname    "caheriaguilar.com"
                                                                   :branch-name "main"
                                                                   :author      "caheri"}
                                                                  {:content "secret draft"})
        app (->> components
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]

    (testing "sanity: the other tenant's branch really has versions"
      (is (some? other-branch-id))
      (is (= 1 (count (articles-api/get-versions (:database components)
                                                 {:branch-id other-branch-id})))))

    (testing "an authorized andrewslai.com request cannot read caheriaguilar.com's versions"
      (is (match? {:status 404}
                  (app (get-version "https://andrewslai.com" other-branch-id)))))))

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
    (is (match? {:status 200 :body (malli/validator portfolio/Portfolio)}
                response))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Registration APIs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment
  ;; Requires a live internet connection to check stripe
  (deftest payments-test
    (let [app      (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                         "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                         "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                         "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                        (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                        env/prepare-kaleidoscope
                        kaleidoscope/kaleidoscope-app
                        tu/wrap-clojure-response)
          response (app (-> (mock/request :post "/v1/payments")
                            (mock/json-body {:amount 1200 :currency "usd"})))]
      (is (match? {:status  200
                   :headers {"Content-Type" "application/json; charset=utf-8"}
                   :body    (malli/validator kaleidoscope/PaymentIntent)}
                  response))))

  ;; TODO: Mock the Route 53 response
  (deftest check-domain
    (let [app      (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                         "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                         "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                         "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                        (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                        env/prepare-kaleidoscope
                        kaleidoscope/kaleidoscope-app
                        tu/wrap-clojure-response)
          response (app (mock/request :get "/check-domain?domain=andrewslai.com"))]
      (is (match? {:status  200
                   :headers {"Content-Type" "application/json; charset=utf-8"}
                   :body    {:domain    "andrewslai.test"
                             :available true
                             :prices    {:registration-price     {:price 15 :currency "USD"}
                                         :transfer-price         {:price 15 :currency "USD"}
                                         :renewal-price          {:price 15 :currency "USD"}
                                         :change-ownership-price {:price 0 :currency "USD"}
                                         :restoration-price      {:price 57 :currency "USD"}
                                         :net                    "net"}}}
                  response)))))

(defn- registration-app
  []
  (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
        "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
        "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
        "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
       (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
       env/prepare-kaleidoscope
       kaleidoscope/kaleidoscope-app
       tu/wrap-clojure-response))

;; reitit-stripe-routes is not mounted in kaleidoscope-app yet (not
;; production-ready), so /v1/payments is currently unreachable - these tests
;; are disabled until it's remounted. They exercise the coercion-failure and
;; rate-limit paths only, which run *before* the handler, so none of these
;; requests would reach Stripe and none require network access or API keys.
(comment
  (deftest payments-validation-test
    (let [app (registration-app)]
      (mw/reset-rate-limits!)
      (testing "Amount below Stripe's practical minimum is rejected before reaching Stripe"
        (is (match? {:status 400}
                    (app (-> (mock/request :post "/v1/payments")
                             (mock/json-body {:amount 10 :currency "usd"}))))))
      (testing "Amount above the app-level cap is rejected"
        (is (match? {:status 400}
                    (app (-> (mock/request :post "/v1/payments")
                             (mock/json-body {:amount 1000000000 :currency "usd"}))))))
      (testing "Unsupported currency is rejected"
        (is (match? {:status 400}
                    (app (-> (mock/request :post "/v1/payments")
                             (mock/json-body {:amount 1200 :currency "eur"}))))))
      (testing "Non-integer amount is rejected"
        (is (match? {:status 400}
                    (app (-> (mock/request :post "/v1/payments")
                             (mock/json-body {:amount "1200" :currency "usd"}))))))))

  (deftest payments-rate-limit-test
    (let [app     (registration-app)
          ;; Invalid amount so coercion always short-circuits before Stripe -
          ;; the rate limiter runs ahead of coercion, so this still exercises it.
          request #(-> (mock/request :post "/v1/payments")
                       (mock/json-body {:amount -1 :currency "usd"}))]
      (mw/reset-rate-limits!)
      (dotimes [_ 10]
        (app (request)))
      (testing "11th request from the same IP within the window is rate limited"
        (is (match? {:status 429}
                    (app (request))))))))

;; reitit-registration-routes is not mounted in kaleidoscope-app yet (not
;; production-ready), so /check-domain and /registration are currently
;; unreachable - these tests are disabled until it's remounted.
(comment
  (deftest check-domain-validation-test
    (let [app (registration-app)]
      (mw/reset-rate-limits!)
      (testing "Domain without a TLD is rejected before reaching AWS"
        (is (match? {:status 400}
                    (app (mock/request :get "/check-domain?domain=localhost")))))
      (testing "Domain with an invalid character is rejected"
        (is (match? {:status 400}
                    (app (mock/request :get "/check-domain?domain=not_a_domain.com")))))))

  (deftest check-domain-rate-limit-test
    (let [app     (registration-app)
          request #(mock/request :get "/check-domain?domain=localhost")]
      (mw/reset-rate-limits!)
      (dotimes [_ 20]
        (app (request)))
      (testing "21st request from the same IP within the window is rate limited"
        (is (match? {:status 429}
                    (app (request))))))))

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
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"
                  "KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE" "println"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)

        create-response (app (make-example-file-upload-request-png "https://andrewslai.test" "example-image.png"))
        get-response    (app (-> (mock/request :get "https://andrewslai.test/v2/photos" {:filename "mobile.png"})
                                 (mock/header "Authorization" "Bearer x")))
        image-id        (get-in get-response [:body 0 :id])]

    (testing "Upload works"
      (is (match? {:status  201
                   :headers {"Content-Type" "application/json"}
                   :body    [{:photo-id string?
                              :versions (m/in-any-order [{:filename "raw.png"}
                                                         {:filename "thumbnail.png"}
                                                         {:filename "gallery.png"}
                                                         {:filename "monitor.png"}
                                                         {:filename "mobile.png"}])}]}
                  create-response)))

    (testing "Retrieval works"
      (testing "Using query params to find `mobile` images"
        (is (match? {:status 200
                     :body   (m/in-any-order [{:filename "mobile.png"}
                                              {:filename "gallery.png"}
                                              {:filename "raw.png"}
                                              {:filename "monitor.png"}
                                              {:filename "thumbnail.png"}])}
                    get-response)))
      (testing "Using the image ID"
        (is (match? {:status  200
                     :headers {"Content-Type" #"application/json"}
                     :body    [{:id             image-id
                                :path           (str "/v2/photos/" image-id "/raw.png")
                                :image-category "raw"
                                :hostname       "andrewslai.test"}
                               {:path           (str "/v2/photos/" image-id "/thumbnail.png")
                                :image-category "thumbnail"}
                               {:path           (str "/v2/photos/" image-id "/gallery.png")
                                :image-category "gallery"}
                               {:path           (str "/v2/photos/" image-id "/monitor.png")
                                :image-category "monitor"}
                               {:path           (str "/v2/photos/" image-id "/mobile.png")
                                :image-category "mobile"}]}
                    (app (mock/request :get (str "https://andrewslai.test/v2/photos/" image-id))))))
      (testing "Direct access via HTTP API"
        (is (match? {:status 200}
                    (app (mock/request :get (format "https://andrewslai.test/v2/photos/%s/raw.png" image-id)))))))

    (testing "Matching Etags return a 304 - not modified response"
      (let [image-path (str "media/" image-id "/raw.png")]
        (is (match? {:status 304}
                    (app (-> (mock/request :get (str "https://andrewslai.test/v2/photos/" image-id "/raw.png"))
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

      (let [second-user-group-id "22222222-2222-2222-2222-222222222222"]
        (testing "A different user ID creates a second group"
          (is (match? {:status 200
                       :body   [{:id string?}]}
                      (app (-> (mock/request :put (format "https://andrewslai.com/groups/%s" second-user-group-id))
                               (mock/header "Authorization" "Bearer user second-user")
                               (mock/json-body {:display-name "my-display-name"}))))))

        (testing "I can retrieve groups I own"
          (let [response (app (-> (mock/request :get "https://andrewslai.com/groups")
                                  (mock/header "Authorization" "Bearer user first-user")))]
            (is (match? {:status 200
                         :body   [{:group-id id}]}
                        response))
            (is (= 1 (count (:body response))))))

        ;; Also an admin-override regression test — see the comment on the
        ;; equivalent theme-update test below for why "first-user" here
        ;; already holds andrewslai.com:admin.
        (testing "I cannot delete groups I do not own, even though I hold admin on this site"
          (log/with-min-level :error
            (is (match? {:status 404}
                        (app (-> (mock/request :delete (format "https://andrewslai.com/groups/%s" second-user-group-id))
                                 (mock/header "Authorization" "Bearer user first-user"))))))))

      (testing "Deleted group doesn't exist"
        (is (match? {:status 204}
                    (app (-> (mock/request :delete (format "https://andrewslai.com/groups/%s" id))
                             (mock/header "Authorization" "Bearer user first-user")))))
        (is (match? {:status 200
                     :body   empty?}
                    (app (-> (mock/request :get "https://andrewslai.com/groups")
                             (mock/header "Authorization" "Bearer user first-user")))))))))

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
                               (mock/header "Authorization" "Bearer user first-user"))))))))))

(deftest index.html-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (is (match? {:status  200
                 :headers {"Cache-Control" cc/revalidate-0s}
                 :body    [{:name "afile"} {:name "adir" :type "directory"}]}
                (app (-> (mock/request :get "https://andrewslai.com/media/")
                         (mock/header "Authorization" "Bearer x")))))))

(deftest albums-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
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
                                                  :created-at  string?))}
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
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"
                  "KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE" "println"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)

        ;; Test logic
        [photo-upload-result] (:body (app (make-example-file-upload-request-png "https://andrewslai.com" "example-image.png")))
        album-create-result   (:body (app (-> (mock/request :post "https://andrewslai.com/albums")
                                              (mock/json-body example-album))))
        photo-id              (str (:photo-id photo-upload-result))
        album-id              (:id album-create-result)]

    (testing "Album is empty to start"
      (is (match? {:status 200 :body []}
                  (app (-> (mock/request :get (format "https://andrewslai.com/albums/%s/contents" album-id)))))))

    (let [result           (app (-> (mock/request :post (format "https://andrewslai.com/albums/%s/contents" album-id))
                                    (mock/json-body [{:id photo-id}])))
          album-content-id (get-in result [:body 0 :album-content-id])]
      (testing "Successfully added photo to album"
        (is (match? {:status 200 :body [{:album-content-id string-uuid?}]}
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
          (is (match? {:status 404 :body {:reason string?}}
                      (app (mock/request :get (format "https://andrewslai.com/albums/%s/contents/%s" album-id album-content-id))))))))))

(deftest contents-retrieval-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "public-access"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"
                  "KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE" "println"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)

        ;; Test logic
        [{photo-1-id :photo-id}] (:body (app (make-example-file-upload-request-png "https://andrewslai.com" "example-image.png")))
        [{photo-2-id :photo-id}] (:body (app (make-example-file-upload-request-png "https://andrewslai.com" "example-image.png")))
        {album-1-id :id}         (:body (app (-> (mock/request :post "https://andrewslai.com/albums")
                                                 (mock/json-body example-album))))
        {album-2-id :id}         (:body (app (-> (mock/request :post "https://andrewslai.com/albums")
                                                 (mock/json-body example-album-2))))]

    ;; Add a photo to two separate albums
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
                                       :photo-id   (str photo-1-id)}
                                      {:album-name (:album-name example-album)
                                       :photo-id   (str photo-2-id)}
                                      {:album-name (:album-name example-album-2)
                                       :photo-id   (str photo-1-id)}]}
                  (app (-> (mock/request :get "https://andrewslai.com/albums/-/contents"))))))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Audiences API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest audiences-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)

        result   (app (-> (mock/request :post "https://andrewslai.com/groups")
                          (mock/header "Authorization" "Bearer x")
                          (mock/json-body {:display-name "my-display-name"})))
        group-id (get-in result [:body 0 :id])]

    (testing "No audience to start"
      (is (match? {:status 404}
                  (app (-> (mock/request :get "https://andrewslai.com/article-audiences")
                           (mock/header "Authorization" "Bearer x"))))))

    (let [add-response (app (-> (mock/request :put "https://andrewslai.com/article-audiences")
                                (mock/header "Authorization" "Bearer x")
                                (mock/json-body {:article-id 1
                                                 :group-id   group-id})))]
      (testing "Add an audience to an article"
        (is (match? {:status 200
                     :body   [{:id string-uuid?}]}
                    add-response)))

      (testing "Audience exists"
        (is (match? {:status 200
                     :body   [{:article-id 1
                               :hostname   "andrewslai.com"
                               :group-id   group-id}]}
                    (app (-> (mock/request :get "https://andrewslai.com/article-audiences")
                             (mock/header "Authorization" "Bearer x"))))))

      (testing "Query param search works"
        (is (match? {:status 200
                     :body   [{:article-id 1
                               :hostname   "andrewslai.com"
                               :group-id   group-id}]}
                    (app (-> (mock/request :get "https://andrewslai.com/article-audiences?article-id=1")
                             (mock/header "Authorization" "Bearer x")))))
        (is (match? {:status 404}
                    (app (-> (mock/request :get "https://andrewslai.com/article-audiences?article-id=1000000")
                             (mock/header "Authorization" "Bearer x"))))))

      (testing "Delete the audience"
        (is (match? {:status 200
                     :body   []}
                    (app (-> (mock/request :delete (format "https://andrewslai.com/article-audiences/%s"
                                                           (get-in add-response [:body 0 :id])))
                             (mock/header "Authorization" "Bearer x")))))

        (is (match? {:status 404}
                    (app (-> (mock/request :get "https://andrewslai.com/article-audiences")
                             (mock/header "Authorization" "Bearer x")))))))))

(deftest themes-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "custom-authenticated-user"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]

    (testing "No themes to start"
      (is (match? {:status 404}
                  (app (mock/request :get "https://andrewslai.com/themes")))))

    (let [add-response (app (-> (mock/request :post "https://andrewslai.com/themes")
                                (mock/header "Authorization" "Bearer x")
                                (mock/json-body {:config       {:primary {:main "#ABC123"}}
                                                 :display-name "My New Theme"})))
          theme-id     (get-in add-response [:body :id])]
      (testing "Add a theme"
        (is (match? {:status 200
                     :body   {:config       some?
                              :id           string-uuid?
                              :display-name "My New Theme"}}
                    add-response)))

      (testing "theme exists"
        (is (match? {:status 200
                     :body   [{:config       {:primary {:main "#ABC123"}}
                               :id           string-uuid?
                               :display-name "My New Theme"}]}
                    (app (-> (mock/request :get "https://andrewslai.com/themes")
                             (mock/header "Authorization" "Bearer x"))))))

      (testing "theme can be queried"
        (is (match? {:status 200
                     :body   [{:config       {:primary {:main "#ABC123"}}
                               :id           string-uuid?
                               :display-name "My New Theme"}]}
                    (app (-> (mock/request :get (format "https://andrewslai.com/themes?id=%s" theme-id))
                             (mock/header "Authorization" "Bearer x")))))
        (is (match? {:status 404}
                    (app (-> (mock/request :get (format "https://andrewslai.com/themes?id=%s" (java.util.UUID/randomUUID)))
                             (mock/header "Authorization" "Bearer x"))))))

      ;; This also serves as the admin-override regression test (PLAN.md
      ;; step 1: decided "no admin override"): KALEIDOSCOPE_AUTH_TYPE
      ;; "custom-authenticated-user" grants the base identity
      ;; `andrewslai.com:admin` (see init/env.clj), and the "Bearer user X"
      ;; override only replaces :sub/:email, never :realm_access — so
      ;; "someone-else" below already holds admin on this site. If ownership
      ;; had an admin bypass, this request would succeed; it must still 404.
      (testing "I cannot update a theme I do not own, even though I hold admin on this site"
        (log/with-min-level :error
          (is (match? {:status 404}
                      (app (-> (mock/request :put (format "https://andrewslai.com/themes/%s" theme-id))
                               (mock/header "Authorization" "Bearer user someone-else")
                               (mock/json-body {:config       {:primary {:main "#HIJACKED"}}
                                                :display-name "Hijacked name"})))))
          (is (match? {:status 200
                       :body   [{:config       {:primary {:main "#ABC123"}}
                                 :id           string-uuid?
                                 :display-name "My New Theme"}]}
                      (app (-> (mock/request :get (format "https://andrewslai.com/themes?id=%s" theme-id))
                               (mock/header "Authorization" "Bearer x")))))))

      ;; Cross-site theme test (PLAN.md, "Gap: themes need a compound
      ;; ownership key"): the *same* owner, holding writer/admin roles on
      ;; multiple sites (custom-authenticated-user grants admin on all of
      ;; them), cannot touch their own theme by routing the request through
      ;; a different site's Host header than the one the theme belongs to.
      (testing "I cannot update my own theme via a different site's Host header"
        (log/with-min-level :error
          (is (match? {:status 404}
                      (app (-> (mock/request :put (format "https://sahiltalkingcents.com/themes/%s" theme-id))
                               (mock/header "Authorization" "Bearer x")
                               (mock/json-body {:config       {:primary {:main "#HIJACKED"}}
                                                :display-name "Hijacked via wrong site"})))))
          (is (match? {:status 200
                       :body   [{:config       {:primary {:main "#ABC123"}}
                                 :id           string-uuid?
                                 :display-name "My New Theme"}]}
                      (app (-> (mock/request :get (format "https://andrewslai.com/themes?id=%s" theme-id))
                               (mock/header "Authorization" "Bearer x")))))))

      (testing "Update theme"
        (app (-> (mock/request :put (format "https://andrewslai.com/themes/%s" theme-id))
                 (mock/header "Authorization" "Bearer x")
                 (mock/json-body {:config       {:primary {:main      "#ABC123"
                                                           :new-field "new-test"}}
                                  :display-name "Updated name"})))

        (is (match? {:status 200
                     :body   [{:config       {:primary {:main      "#ABC123"
                                                        :new-field "new-test"}}
                               :id           string-uuid?
                               :display-name "Updated name"}]}
                    (app (-> (mock/request :get (format "https://andrewslai.com/themes?id=%s" theme-id))
                             (mock/header "Authorization" "Bearer x"))))))

      (testing "Delete the theme"
        (is (match? {:status 204}
                    (app (-> (mock/request :delete (format "https://andrewslai.com/themes/%s"
                                                           theme-id))
                             (mock/header "Authorization" "Bearer x")))))

        (is (match? {:status 404}
                    (app (mock/request :get "https://andrewslai.com/themes"))))))))

(comment
  (require '[clj-http.client :as http])

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
                                             clojure.java.io/input-stream)}]}))
