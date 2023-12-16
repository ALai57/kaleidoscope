(ns kaleidoscope.http-api.articles
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [kaleidoscope.api.articles :as articles-api]
            [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.models.articles :as models.articles]
            [ring.util.http-response :refer [conflict not-found ok]]
            [taoensso.timbre :as log]))

(defn ->commit [{:keys [body-params] :as request}]
  (let [defaults {:content ""}]
    (merge defaults
           (select-keys body-params [:branch-id :content :created-at :modified-at]))))

(def reitit-articles-routes
  ["/articles" {:tags    ["articles"]
                :openapi {:security [{:andrewslai-pkce ["roles" "profile"]}]}
                ;; For testing only - this is a mechanism to always get results from a particular
                ;; host URL.
                ;;
                ;;:host      "andrewslai.localhost"
                }
   ["" {:get {:summary   "Retrieve all articles"
              :responses (merge hu/openapi-500
                                {200 {:description "A collection of all articles"
                                      :content     {"application/json"
                                                    {:schema   [:sequential models.articles/GetArticleResponse]
                                                     :examples {"example-articles" {:summary "Example articles response"
                                                                                    :value   [models.articles/example-article
                                                                                              models.articles/example-article-2]}}}}}})
              :handler   (fn [{:keys [components] :as request}]
                           (->> {:hostname (hu/get-host request)}
                                (articles-api/get-articles (:database components))
                                ok))}}]
   ["/:article-url"
    {:get {:summary   "Retrieve a single article"
           :responses (merge hu/openapi-404 hu/openapi-500
                             {200 {:description "A single article"
                                   :content     {"application/json"
                                                 {:schema   models.articles/GetArticleResponse
                                                  :examples {"example-article" {:summary "Example article"
                                                                                :value   models.articles/example-article}}}}}})

           :parameters {:path {:article-url string?}}
           :handler    (fn [{:keys [components path-params] :as request}]
                         (let [article-url (:article-url path-params)]
                           (if-let [article (first (articles-api/get-articles (:database components) {:article-url article-url}))]
                             (ok article)
                             (not-found {:reason "Missing"}))))}}]
   ["/:article-url/branches"
    {:tags ["branches"]
     :get  {:summary    "Retrieve all branches for a specific article"
            :responses  {200 {:description "Branches"
                              :content     {"application/json"
                                            {:schema   [:sequential models.articles/GetBranchResponse]
                                             :examples {"example-branches" {:summary "Example branches"
                                                                            :value   [models.articles/example-branch-1
                                                                                      models.articles/example-branch-2]}}}}}}
            :parameters {:path {:article-url string?}}
            :handler    (fn [{:keys [components path-params] :as request}]
                          (let [article-url (:article-url path-params)]
                            (ok (articles-api/get-branches (:database components) {:article-url article-url}))))}}]
   ;; The two routes below should be relocated to `branches`
   ["/:article-url/branches/:branch-name/publish"
    {:put {:summary   "Publish an article branch"
           :responses {200 {:description "Branches"
                            :content     {"application/json"
                                          {:schema   [:sequential models.articles/GetBranchResponse]
                                           :examples {"example-branch" {:summary "Example branch"
                                                                        :value   models.articles/example-branch-1}}}}}}
           :handler   (fn [{:keys [components path-params] :as request}]
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
    {:post {:summary    "Create a new version (commit) on a branch"
            :responses  {200 {:description "Version"
                              :content     {"application/json"
                                            {:schema   [:sequential models.articles/GetVersionResponse]
                                             :examples {"example-version" {:summary "Example version"
                                                                           :value   models.articles/example-version-1}}}}}
                         409 {:body [:= "Cannot change a published branch"]}}
            :parameters {:path {:article-url string?
                                :branch-name string?}}
            :request    {:description "Version"
                         :content     {"application/json"
                                       {:schema   models.articles/CreateVersionRequest
                                        :examples {"example-version" {:summary "Example version"
                                                                      :value   models.articles/example-version-request}}}}}
            :handler    (fn [{:keys [components path-params] :as request}]
                          (try
                            (let [commit      (->commit request)
                                  branch-name (:branch-name path-params)
                                  article-url (:article-url path-params)
                                  result      (articles-api/new-version! (:database components)
                                                                         {:branch-name   branch-name
                                                                          :hostname      (hu/get-host request)
                                                                          :article-url   article-url
                                                                          :article-tags  (get-in request [:body-params :article-tags] "thoughts")
                                                                          :article-title (get-in request [:body-params :article-title] "[New article]")
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
   ["" {:get {:summary     "Retrieve all published articles"
              :description "This endpoint retrieves all published articles. The endpoint is currently not paginated"
              :responses   (merge hu/openapi-500
                                  {200 {:description "A collection of all published articles"
                                        :content     {"application/json"
                                                      {:schema   [:sequential models.articles/GetCompositionResponse]
                                                       :examples {"example-articles" {:summary "A collection of all published articles"
                                                                                      :value   [models.articles/example-composition-1]}}}}}})

              :handler (fn [{:keys [components] :as request}]
                         (log/infof "models.articles/Getting compositions for host `%s`" (hu/get-host request))
                         (ok (articles-api/get-published-articles (:database components)
                                                                  {:hostname (hu/get-host request)})))}}]
   ["/:article-url"
    {:get {:summary    "Retrieve a single published article"
           :parameters {:path {:article-url string?}}
           :responses  (merge hu/openapi-500 hu/openapi-404
                              {200 {:description "A published article"
                                    :content     {"application/json"
                                                  {:schema   models.articles/GetCompositionResponse
                                                   :examples {"example-article" {:summary "A single published articles"
                                                                                 :value   models.articles/example-composition-1}}}}}})
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
   ["" {:get {:summary     "Retrieve all branches"
              :description "Currently not paginated."
              :responses   (merge hu/openapi-500
                                  {200 {:description "A collection of all branches"
                                        :content     {"application/json"
                                                      {:schema   [:sequential models.articles/GetBranchResponse]
                                                       :examples {"example-branches" {:summary "A collection of all branches"
                                                                                      :value   [models.articles/example-article]}}}}}})

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
        :post {:summary   "Create a new branch"
               :responses (merge hu/openapi-401
                                 {200 {:body [:any]}})
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
     :get  {:summary    "models.articles/Get versions"
            :responses  (merge hu/openapi-401
                               {200 {:description "Versions"
                                     :content     {"application/json"
                                                   {:schema   [:any]
                                                    :examples {"example-versions" {:summary "A single version"
                                                                                   :value   [models.articles/example-version-1]}}}}}})
            :parameters {:path {:branch-id string?}}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (let [branch-id (get-in parameters [:path :branch-id])
                                branches  (articles-api/get-versions (:database components) {:branch-id (Integer/parseInt branch-id)})]
                            (if (empty? branches)
                              (not-found {:reason "Missing"})
                              (ok (reverse (sort-by :created-at branches))))))}}]])
