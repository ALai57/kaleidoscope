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
   [:modified-at   inst?]
   [:published-at  [:maybe inst?]]])

(def GetVersionResponse
  [:map
   [:article-id    :int]
   [:article-tags  :string]
   [:article-title :string]
   [:article-url   :string]
   [:author        :string]
   [:branch-id     :int]
   [:branch-name   :string]
   [:content       :string]
   [:created-at    inst?]
   [:hostname      :string]
   [:modified-at   inst?]
   [:published-at  [:maybe inst?]]])

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

(def CreateVersionRequest
  [:map
   [:article-title {:optional true} :string]
   [:article-tags  {:optional true} :string]
   [:content       :string]])

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
   :published-at  "2022-01-03T00:00:00Z"
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
   :published-at  nil
   :hostname      "andrewslai.localhost"})

(def example-version-request
  {:article-title "My first article"
   :article-tags  "thoughts"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :published-at  "2022-01-03T00:00:00Z"
   :content       "<p>Hello there!</p>"})

(def example-version-1
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-1"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :published-at  "2022-01-03T00:00:00Z"
   :content       "<p>Hello there!</p>"
   :hostname      "andrewslai.localhost"})

(def example-composition-1
  {:article-id    1
   :author        "Andrew Lai"
   :article-url   "my-first-article"
   :article-title "My first article"
   :article-tags  "thoughts"
   :branch-id     1
   :branch-name   "branch-1"
   :created-at    "2022-01-01T00:00:00Z"
   :modified-at   "2022-01-01T00:00:00Z"
   :published-at  "2022-01-03T00:00:00Z"
   :content       "<p>Hello there!</p>"
   :hostname      "andrewslai.localhost"})

(def example-not-found
  {:reason "missing"})

(def ErrorResponse
  [:map])

(def NotFoundResponse
  [:map
   [:reason :string]])

(def NotAuthorizedResponse
  [:any])

(def example-not-authorized
  {})

(def openapi-401
  {404 {:description "Unauthorized"
        :content     {"application/json"
                      {:schema   NotAuthorizedResponse
                       :examples {"not-authorized" {:summary "Not authorized"
                                                    :value   example-not-authorized}}}}}})

(def openapi-404
  {404 {:description "Not found"
        :content     {"application/json"
                      {:schema   NotFoundResponse
                       :examples {"not-found" {:summary "Not found"
                                               :value   example-not-found}}}}}})

(def openapi-500
  {500 {:description "Error response"
        :content     {"application/json"
                      {:schema ErrorResponse}}}})

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
                                {200 {:description "A collection of all articles"
                                      :content     {"application/json"
                                                    {:schema   [:sequential GetArticleResponse]
                                                     :examples {"example-articles" {:summary "Example articles response"
                                                                                    :value   [example-article
                                                                                              example-article-2]}}}}}})
              :handler   (fn [{:keys [components] :as request}]
                           (->> {:hostname (hu/get-host request)}
                                (articles-api/get-articles (:database components))
                                ok))}}]
   ["/:article-url"
    {:get {:summary   "Retrieve a single article"
           :responses (merge openapi-404 openapi-500
                             {200 {:description "A single article"
                                   :content     {"application/json"
                                                 {:schema   GetArticleResponse
                                                  :examples {"example-article" {:summary "Example article"
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
            :responses  {200 {:description "Branches"
                              :content     {"application/json"
                                            {:schema   [:sequential GetBranchResponse]
                                             :examples {"example-branches" {:summary "Example branches"
                                                                            :value   [example-branch-1
                                                                                      example-branch-2]}}}}}}
            :parameters {:path {:article-url string?}}
            :handler    (fn [{:keys [components path-params] :as request}]
                          (let [article-url (:article-url path-params)]
                            (ok (articles-api/get-branches (:database components) {:article-url article-url}))))}}]
   ;; The two routes below should be relocated to `branches`
   ["/:article-url/branches/:branch-name/publish"
    {:put {:summary   "Publish an article branch"
           :responses {200 {:description "Branches"
                            :content     {"application/json"
                                          {:schema   [:sequential GetBranchResponse]
                                           :examples {"example-branch" {:summary "Example branch"
                                                                        :value   example-branch-1}}}}}}
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
                                            {:schema   [:sequential GetVersionResponse]
                                             :examples {"example-version" {:summary "Example version"
                                                                           :value   example-version-1}}}}}
                         409 {:body [:= "Cannot change a published branch"]}}
            :parameters {:path {:article-url string?
                                :branch-name string?}}
            :request    {:description "Version"
                         :content     {"application/json"
                                       {:schema   CreateVersionRequest
                                        :examples {"example-version" {:summary "Example version"
                                                                      :value   example-version-request}}}}}
            :handler    (fn [{:keys [components path-params] :as request}]
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
   ["" {:get {:summary     "Retrieve all published articles"
              :description "This endpoint retrieves all published articles. The endpoint is currently not paginated"
              :responses   (merge openapi-500
                                  {200 {:description "A collection of all published articles"
                                        :content     {"application/json"
                                                      {:schema   [:sequential GetCompositionResponse]
                                                       :examples {"example-articles" {:summary "A collection of all published articles"
                                                                                      :value   [example-composition-1]}}}}}})

              :handler (fn [{:keys [components] :as request}]
                         (log/infof "Getting compositions for host `%s`" (hu/get-host request))
                         (ok (articles-api/get-published-articles (:database components)
                                                                  {:hostname (hu/get-host request)})))}}]
   ["/:article-url"
    {:get {:summary    "Retrieve a single published article"
           :parameters {:path {:article-url string?}}
           :responses  (merge openapi-500 openapi-404
                              {200 {:description "A published article"
                                    :content     {"application/json"
                                                  {:schema   GetCompositionResponse
                                                   :examples {"example-article" {:summary "A single published articles"
                                                                                 :value   example-composition-1}}}}}})
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
              :responses   (merge openapi-500
                                  {200 {:description "A collection of all branches"
                                        :content     {"application/json"
                                                      {:schema   [:sequential GetBranchResponse]
                                                       :examples {"example-branches" {:summary "A collection of all branches"
                                                                                      :value   [example-article]}}}}}})

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
               :responses (merge openapi-401
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
     :get  {:summary    "Get versions"
            :responses  (merge openapi-401
                               {200 {:description "Versions"
                                     :content     {"application/json"
                                                   {:schema   [:any]
                                                    :examples {"example-versions" {:summary "A single version"
                                                                                   :value   [example-version-1]}}}}}})
            :parameters {:path {:branch-id string?}}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (let [branch-id (get-in parameters [:path :branch-id])
                                branches  (articles-api/get-versions (:database components) {:branch-id (Integer/parseInt branch-id)})]
                            (if (empty? branches)
                              (not-found {:reason "Missing"})
                              (ok (reverse (sort-by :created-at branches))))))}}]])
