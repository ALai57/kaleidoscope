(ns kaleidoscope.http-api.articles
  (:require [kaleidoscope.api.articles :as articles-api]
            [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.models.articles] ;; Install specs
            [kaleidoscope.http-api.http-utils :as hu]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.spec.alpha :as s]
            [compojure.api.meta :as compojure-meta]
            [compojure.api.sweet :refer [context GET POST PUT]]
            [ring.util.http-response :refer [not-found ok conflict]]
            [taoensso.timbre :as log]))

(s/def ::message string?)
(s/def ::error-message (s/keys :req-un [::message]))

(defn ->article [article-url {:keys [body-params] :as request}]
  (-> body-params
      (select-keys [:article-name])
      (assoc :article-url article-url
             :hostname    (hu/get-host request)
             :author      (oidc/get-full-name (:identity request)))))

(defn ->commit [{:keys [body-params] :as request}]
  (select-keys body-params [:branch-id :content :created-at :modified-at]))

;;
;; Reitit
;;

(def GetArticleResponse
  [:map
   [:id            :int]
   [:author        :string]
   [:article-url   :string]
   [:article-title :string]
   [:hostname      :string]
   [:modified-at   inst?]
   [:created-at    inst?]])

(def GetBranchResponse
  [:map
   [:article-id    :int]
   [:article-title :string]
   [:article-url   :string]
   [:author        :string]
   [:branch-id     :int]
   [:branch-name   :string]
   [:created-at    inst?]
   [:hostname      :string]
   [:modified-at   inst?]])

(def GetCompositionResponse
  [:map
   [:article-id    :int]
   [:article-title :string]
   [:article-url   :string]
   [:author        :string]
   [:branch-id     :int]
   [:branch-name   :string]
   [:created-at    inst?]
   [:content       :string]
   [:hostname      :string]
   [:modified-at   inst?]
   [:version-id    :int]])

(def example-article
  {:id            1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :hostname      "andrewslai.localhost"})

(def example-article-2
  {:id            2
   :author        "Andrew Lai"
   :article-url   "my-second-article"
   :article-title "My second article"
   :article-tags  "thoughts"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :hostname      "andrewslai.localhost"})

(def example-branch-1
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-1"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :hostname      "andrewslai.localhost"})

(def example-branch-2
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-2"
   :created-at    "2022-01-02T00:00:00Z"
   :modified-at   "2022-01-02T00:00:00Z"
   :hostname      "andrewslai.localhost"})

(def example-not-found
  {:reason "missing"})

(def ErrorResponse
  [:map])

(def NotFoundResponse
  [:map
   [:reason :string]])

(defn json-examples
  [responses]
  {:content
   {"application/json"
    {:examples responses}}})

(def openapi-404
  {404 {:content {"application/json"
                  {:description "Not found"
                   :schema      NotFoundResponse
                   :examples    {"not-found" {:summary "Not found"
                                              :value   example-not-found}}}}}})

(def openapi-500
  {500 {:content {"application/json"
                  {:description "Error response"
                   :schema      ErrorResponse}}}})

(def reitit-articles-routes
  ["/articles" {:tags    ["articles"]
                :openapi {:security [{:andrewslai-pkce ["roles" "profile"]}]}
                ;; For testing only - this is a mechanism to always get results from a particular
                ;; host URL.
                ;;
                ;;:host      "andrewslai.localhost"
                }
   ["" {:get {:summary   "Retrieve all articles"
              :responses (merge openapi-500
                                {200 {:content {"application/json"
                                                {:description "A collection of all articles"
                                                 :schema      [:sequential GetArticleResponse]
                                                 :examples    {"example-articles" {:summary "Example articles response"
                                                                                   :value   [example-article
                                                                                             example-article-2]}}}}}})
              :handler   (fn [{:keys [components] :as request}]
                           (->> {:hostname (hu/get-host request)}
                                (articles-api/get-articles (:database components))
                                ok))}}]
   ["/:article-url"
    {:get {:summary   "Retrieve a single article"
           :responses (merge openapi-404 openapi-500
                             {200 {:content {"application/json"
                                             {:description "A single article"
                                              :schema      GetArticleResponse
                                              :examples    {"example-article" {:summary "Example article"
                                                                               :value   example-article}}}}}})

           :parameters {:path {:article-url string?}}
           :handler    (fn [{:keys [components path-params] :as request}]
                         (let [article-url (:article-url path-params)]
                           (if-let [article (first (articles-api/get-articles (:database components) {:article-url article-url}))]
                             (ok article)
                             (not-found {:reason "Missing"}))))}}]
   ["/:article-url/branches"
    {:tags ["branches"]
     :get  {:summary    "Retrieve all branches for a specific article"
            :responses  {200 {:content {"application/json"
                                        {:description "Branches"
                                         :schema      [:sequential GetBranchResponse]
                                         :examples    {"example-branches" {:summary "Example branches"
                                                                           :value   [example-branch-1
                                                                                     example-branch-2]}}}}}}
            :parameters {:path {:article-url string?}}
            :handler    (fn [{:keys [components path-params] :as request}]
                          (let [article-url (:article-url path-params)]
                            (ok (articles-api/get-branches (:database components) {:article-url article-url}))))}}]
   ;; The two routes below should be relocated to `branches`
   ["/:article-url/branches/:branch-name/publish"
    {:put {:openapi {:summary   "Publish an article branch"
                     :produces  #{"application/json"}
                     :responses {200 (json-examples {"example-article" {:summary "A single article"
                                                                        :value   example-article}})}}
           :handler (fn [{:keys [components path-params] :as request}]
                      (let [branch-name (:branch-name path-params)
                            article-url (:article-url path-params)]
                        (try
                          (log/infof "Publishing article %s and branch %s" article-url branch-name)
                          (let [[{:keys [branch-id]}] (articles-api/get-branches (:database components) {:branch-name branch-name
                                                                                                         :article-url article-url})
                                result                (articles-api/publish-branch! (:database components) branch-id)]
                            (ok result))
                          (catch Exception e
                            (log/error "Caught exception " e)))))}}]
   ["/:article-url/branches/:branch-name/versions"
    {:post {:openapi {:summary   "Create a new version (commit) on a branch"
                      :consumes  #{"application/json"}
                      :produces  #{"application/json"}
                      :responses {200 (json-examples {"example-article" {:summary "A single article"
                                                                         :value   example-article}})}}
            :handler (fn [{:keys [components path-params] :as request}]
                       (try
                         (let [commit      (->commit request)
                               branch-name (:branch-name path-params)
                               article-url (:article-url path-params)
                               result      (articles-api/new-version! (:database components)
                                                                      {:branch-name   branch-name
                                                                       :hostname      (hu/get-host request)
                                                                       :article-url   article-url
                                                                       :article-tags  (get-in request [:params :article-tags] "thoughts")
                                                                       :article-title (get-in request [:params :article-title])
                                                                       :author        (oidc/get-full-name (:identity request))}
                                                                      commit)]
                           (log/info result)
                           (ok result))
                         (catch Exception e
                           (log/error "Caught exception " e)
                           (conflict (ex-message e)))))}}]])

(def reitit-compositions-routes
  ["/compositions" {:tags ["compositions"]
                    ;; For testing only - this is a mechanism to always get results from a particular
                    ;; host URL.
                    ;;
                    ;;:host      "andrewslai.localhost"
                    }
   ["" {:get {:openapi {:summary     "Retrieve all published articles"
                        :description (str "This endpoint retrieves all published articles. "
                                          "The endpoint is currently not paginated")
                        :produces    #{"application/json"}
                        :responses   {500 {:body ErrorResponse}
                                      200 (json-examples {"example-articles" {:summary "A collection of all published articles"
                                                                              :body    [:sequential GetCompositionResponse]
                                                                              :value   [example-article]}})}}

              :responses {200 {:description "A collection of all published articles"
                               :body        [:sequential GetCompositionResponse]}
                          500 {:body ErrorResponse}}

              :handler (fn [{:keys [components] :as request}]
                         (log/infof "Getting compositions for host `%s`" (hu/get-host request))
                         (ok (articles-api/get-published-articles (:database components)
                                                                  {:hostname (hu/get-host request)})))}}]
   ["/:article-url"
    {:get {:openapi {:summary    "Retrieve a single published article"
                     :produces   #{"application/json"}
                     :parameters {:path {:article-url string?}}
                     :responses  {200 (json-examples {"example-article" {:summary "A single article"
                                                                         :value   example-article}})}}

           :responses {200 {:body GetCompositionResponse}
                       404 {:body NotFoundResponse}
                       500 {:body ErrorResponse}}

           :parameters {:path {:article-url string?}}
           :handler    (fn [{:keys [components parameters] :as request}]
                         (log/infof "Retrieving composition %s" (get-in parameters [:path :article-url]))
                         (let [result (articles-api/get-published-articles (:database components) {:article-url (get-in parameters [:path :article-url])})]
                           (if (empty? result)
                             (not-found {:reason "Missing"})
                             (ok (first result)))))}}]])

(def reitit-branches-routes
  ["/branches" {:tags     ["branches"]
                :security [{:andrewslai-pkce ["roles" "profile"]}]
                ;; For testing only - this is a mechanism to always get results from a particular
                ;; host URL.
                ;;
                ;;:host      "andrewslai.localhost"
                }
   ["" {:get {:openapi {:summary     "Retrieve all branches"
                        :description (str "This endpoint retrieves all branches. "
                                          "The endpoint is currently not paginated")
                        :produces    #{"application/json"}
                        :responses   {500 {:body ErrorResponse}
                                      200 (json-examples {"example-branches" {:summary "A collection of all published articles"
                                                                              :body    [:sequential GetBranchResponse]
                                                                              :value   [example-article]}})}}

              :responses {200 {:description "A collection of all branches"
                               :body        [:sequential GetBranchResponse]}
                          500 {:body ErrorResponse}}

              :handler (fn [{:keys [components parameters] :as request}]
                         (let [query-params (-> csk/->kebab-case-keyword
                                                (cske/transform-keys (:query parameters))
                                                (select-keys [:article-id :article-url])
                                                (assoc :hostname (hu/get-host request)))
                               branches     (articles-api/get-branches (:database components)
                                                                       query-params)]
                           (if (empty? branches)
                             (not-found {:reason "Missing"})
                             (ok branches))))}
        :post {:openapi {:summary  "Create a new branch"
                         :produces #{"application/json"}
                         :consumes #{"application/json"}}

               :responses {200 {:body [:any]}
                           401 {:body [:any]}}
               :handler   (fn [{:keys [components body-params] :as request}]
                            (try
                              (ok (articles-api/create-branch! (:database components)
                                                               (assoc body-params
                                                                      :hostname (hu/get-host request)
                                                                      :author   (oidc/get-full-name (:identity request)))))
                              (catch Exception e
                                (log/error "Caught exception " e))))}}]

   ["/:branch-id/versions"
    {:tags ["versions"]
     :get  {:openapi {:summary    "Get versions"
                      :produces   #{"application/json"}
                      :parameters {:path {:branch-id string?}}
                      :responses  {200 (json-examples {"example-versions" {:summary "A single version"
                                                                           :value   example-article}})}}

            :responses {200 {:body [:any]}
                        401 {:body [:any]}}

            :parameters {:path {:branch-id string?}}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (let [branch-id (get-in parameters [:path :branch-id])
                                branches  (articles-api/get-versions (:database components) {:branch-id (Integer/parseInt branch-id)})]
                            (if (empty? branches)
                              (not-found {:reason "Missing"})
                              (ok (reverse (sort-by :created-at branches))))))}}]])
