(ns kaleidoscope.http-api.recipes
  (:require [clojure.string :as str]
            [kaleidoscope.api.recipes :as recipes-api]
            [kaleidoscope.api.recipe-scraper :as scraper]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.models.recipes :as models.recipes]
            [ring.util.http-response :refer [bad-request not-found ok unprocessable-entity]]
            [taoensso.timbre :as log]))

(defn ->slug
  "Derive a URL slug from a title, mirroring the frontend `titleToSlug`:
  lower-case, non-alphanumerics to hyphens, trim repeats. Used only when a
  request omits :recipe-url (direct API use); the frontend sends its own."
  [title]
  (-> (or title "")
      str/lower-case
      str/trim
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- validation-error?
  [e]
  (= :validation (:type (ex-data e))))

(defn- handle-write
  "Run a recipe write, turning validation failures (one-per-group, unknown
  label) into a 400 rather than a 500."
  [f]
  (try
    (let [result (f)]
      (if result (ok result) (not-found {:reason "Missing"})))
    (catch clojure.lang.ExceptionInfo e
      (if (validation-error? e)
        (bad-request {:error (ex-message e)})
        (throw e)))))

(def reitit-recipes-routes
  ["/recipes" {:tags     ["recipes"]
               :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get  {:summary    "List recipes visible to the caller (access-filtered)"
               :responses  {200 {:body [:sequential models.recipes/GetRecipeResponse]}}
               :parameters {:query [:map {:closed false}
                                    [:ingredient {:optional true} :string]
                                    [:label-id {:optional true} :uuid]]}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (recipes-api/get-visible-recipes
                                  (:database components)
                                  (merge {:hostname (hu/get-host request)}
                                         (:query parameters))
                                  (:identity request))))}
        :post {:summary    "Create a recipe"
               :responses  {200 {:body models.recipes/GetRecipeResponse}}
               :parameters {:body models.recipes/CreateRecipeRequest}
               :handler    (fn [{:keys [components parameters identity] :as request}]
                             (let [{:keys [content recipe-url] :as body} (:body parameters)]
                               (handle-write
                                #(recipes-api/create-recipe!
                                  (:database components)
                                  (merge body
                                         {:hostname   (hu/get-host request)
                                          :recipe-url (or (not-empty recipe-url)
                                                          (->slug (:title content)))
                                          :author     (or (:name identity) (:user-id identity))})))))}}]

   ["/scrape"
    {:post {:summary    "Fetch + extract a recipe draft from a URL (does not save)"
            :responses  (merge hu/openapi-401
                               {200 {:body models.recipes/ScrapeResult}
                                422 {:body [:map [:reason :string]]}})
            :parameters {:body [:map [:url :string]]}
            :handler    (fn [{:keys [components parameters] :as _request}]
                          (let [url     (get-in parameters [:body :url])
                                api-key (:api-key (:workflow-executor components))
                                fetcher (:recipe-fetcher components)]
                            ;; Expected scrape outcomes become a 422 the client
                            ;; can act on. Anything else — including a Firecrawl
                            ;; :render-failed — propagates to the Bugsnag
                            ;; exception-reporter middleware, which reports it.
                            (try
                              (ok (scraper/scrape {:api-key api-key :fetcher fetcher} url))
                              (catch clojure.lang.ExceptionInfo e
                                (if (#{:fetch-failed :bot-blocked :no-recipe-found :blocked-url}
                                     (:reason (ex-data e)))
                                  (unprocessable-entity {:reason (name (:reason (ex-data e)))})
                                  (throw e))))))}}]

   ["/:recipe-url"
    {:get    {:summary    "Get a single recipe (access-checked)"
              :responses  (merge hu/openapi-404 {200 {:body models.recipes/GetRecipeResponse}})
              :parameters {:path {:recipe-url :string}}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (if-let [recipe (first (recipes-api/get-visible-recipes
                                                    (:database components)
                                                    {:hostname   (hu/get-host request)
                                                     :recipe-url (get-in parameters [:path :recipe-url])}
                                                    (:identity request)))]
                              (ok recipe)
                              (not-found {:reason "Missing"})))}
     :put    {:summary    "Update a recipe's editable fields and labels"
              :responses  (merge hu/openapi-404 {200 {:body models.recipes/GetRecipeResponse}})
              :parameters {:path {:recipe-url :string}
                           :body models.recipes/UpdateRecipeRequest}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (handle-write
                             #(recipes-api/update-recipe!
                               (:database components)
                               (hu/get-host request)
                               (get-in parameters [:path :recipe-url])
                               (:body parameters))))}
     :delete {:summary    "Delete a recipe"
              :responses  {200 {:body [:any]}}
              :parameters {:path {:recipe-url :string}}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (recipes-api/delete-recipe! (:database components)
                                                        (hu/get-host request)
                                                        (get-in parameters [:path :recipe-url]))
                            (ok {:deleted (get-in parameters [:path :recipe-url])}))}}]])

(def reitit-recipe-labels-routes
  ["/recipe-labels" {:tags     ["recipe-labels"]
                     :security [{:andrewslai-pkce ["roles" "profile"]}]}
   ["" {:get  {:summary  "All labels for the tenant, each with its group name"
               :responses {200 {:body [:sequential models.recipes/RecipeLabel]}}
               :handler  (fn [{:keys [components] :as request}]
                           (ok (recipes-api/get-labels (:database components)
                                                       {:hostname (hu/get-host request)})))}
        :post {:summary    "Create a label (optionally in a group)"
               :responses  {200 {:body models.recipes/RecipeLabel}}
               :parameters {:body [:map
                                   [:name :string]
                                   [:group-id {:optional true} [:maybe :uuid]]]}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (recipes-api/create-label!
                                  (:database components)
                                  (assoc (:body parameters) :hostname (hu/get-host request)))))}}]
   ["/:id" {:put    {:summary    "Rename a label"
                     :parameters {:path {:id :uuid}
                                  :body [:map [:name :string]]}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (ok (recipes-api/rename-label!
                                        (:database components)
                                        (get-in parameters [:path :id])
                                        (hu/get-host request)
                                        (get-in parameters [:body :name]))))}
            :delete {:summary    "Delete a label"
                     :parameters {:path {:id :uuid}}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (recipes-api/delete-label! (:database components)
                                                              (get-in parameters [:path :id])
                                                              (hu/get-host request))
                                   (ok {:deleted (get-in parameters [:path :id])}))}}]])

(def reitit-recipe-label-groups-routes
  ["/recipe-label-groups" {:tags     ["recipe-labels"]
                           :security [{:andrewslai-pkce ["roles" "profile"]}]}
   ["" {:get  {:summary   "All label groups for the tenant"
               :responses {200 {:body [:any]}}
               :handler   (fn [{:keys [components] :as request}]
                            (ok (recipes-api/get-label-groups (:database components)
                                                              {:hostname (hu/get-host request)})))}
        :post {:summary    "Create a label group"
               :parameters {:body [:map [:name :string]]}
               :responses  {200 {:body [:any]}}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (recipes-api/create-label-group!
                                  (:database components)
                                  (assoc (:body parameters) :hostname (hu/get-host request)))))}}]
   ["/:id" {:put    {:summary    "Rename a label group"
                     :parameters {:path {:id :uuid}
                                  :body [:map [:name :string]]}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (ok (recipes-api/rename-label-group!
                                        (:database components)
                                        (get-in parameters [:path :id])
                                        (hu/get-host request)
                                        (get-in parameters [:body :name]))))}
            :delete {:summary    "Delete a label group (cascades to its labels)"
                     :parameters {:path {:id :uuid}}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (recipes-api/delete-label-group! (:database components)
                                                                    (get-in parameters [:path :id])
                                                                    (hu/get-host request))
                                   (ok {:deleted (get-in parameters [:path :id])}))}}]])

(def reitit-recipe-audiences-routes
  ["/recipe-audiences" {:tags     ["recipe-audiences"]
                        :security [{:andrewslai-pkce ["roles" "profile"]}]}
   ["" {:get {:summary    "List shares for a recipe"
              :parameters {:query [:map {:closed false} [:recipe-id {:optional true} :uuid]]}
              :responses  (merge hu/openapi-404 {200 {:body [:any]}})
              :handler    (fn [{:keys [components parameters] :as request}]
                            (let [audiences (recipes-api/get-recipe-audiences
                                             (:database components)
                                             (merge {:hostname (hu/get-host request)}
                                                    (:query parameters)))]
                              (if (empty? audiences)
                                (not-found)
                                (ok audiences))))}
        :put {:summary    "Share a recipe with a group"
              :parameters {:body [:map
                                  [:recipe-id :uuid]
                                  [:group-id :uuid]]}
              :responses  {200 {:body [:any]}}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (let [{:keys [recipe-id group-id]} (:body parameters)]
                              (ok (recipes-api/add-audience-to-recipe!
                                   (:database components)
                                   {:id recipe-id :hostname (hu/get-host request)}
                                   {:id group-id}))))}}]
   ["/:audience-id" {:delete {:summary    "Unshare"
                              :parameters {:path {:audience-id :uuid}}
                              :responses  {200 {:body [:any]}}
                              :handler    (fn [{:keys [components parameters] :as _request}]
                                            (ok (recipes-api/delete-recipe-audience!
                                                 (:database components)
                                                 (get-in parameters [:path :audience-id]))))}}]])
