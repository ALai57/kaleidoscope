(ns kaleidoscope.http-api.recipes
  (:require [clojure.string :as str]
            [kaleidoscope.api.authorization :as authz]
            [kaleidoscope.api.recipes :as recipes-api]
            [kaleidoscope.api.recipe-scraper :as scraper]
            [kaleidoscope.api.recipe-timeline :as timeline-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.models.recipes :as models.recipes]
            [ring.util.http-response :refer [bad-gateway bad-request conflict not-found ok service-unavailable unprocessable-entity]]
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

(defn- handle-write
  "Run a recipe write, turning validation failures (one-per-group, unknown
  label) into a 400 and slug collisions into a 409 rather than a 500."
  [f]
  (try
    (let [result (f)]
      (if result (ok result) (not-found {:reason "Missing"})))
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :validation (bad-request {:error (ex-message e)})
        :conflict   (conflict {:error (ex-message e)})
        (throw e)))))

(def ^:private max-images 5)
(def ^:private max-image-bytes (* 5 1024 1024))
(def ^:private allowed-image-types #{"image/jpeg" "image/png" "image/webp" "image/gif"})

(defn- file-upload?
  [x] (and (map? x) (:tempfile x) (:filename x)))

(defn multipart-images
  "Extract uploaded image files from multipart `params` into
  [{:content-type string :bytes byte-array}]. Throws ex-info {:type :validation
  :reason ..} on no image / too many / unsupported type / oversize."
  [params]
  (let [files (->> params vals (filter file-upload?))]
    (when (empty? files)
      (throw (ex-info "No image uploaded" {:type :validation :reason :no-image})))
    (when (> (count files) max-images)
      (throw (ex-info (str "At most " max-images " images per import")
                      {:type :validation :reason :too-many-images})))
    (mapv (fn [{:keys [content-type tempfile]}]
            (when-not (contains? allowed-image-types content-type)
              (throw (ex-info (str "Unsupported image type: " content-type)
                              {:type :validation :reason :unsupported-type})))
            (let [bytes (java.nio.file.Files/readAllBytes (.toPath ^java.io.File tempfile))]
              (when (> (alength bytes) max-image-bytes)
                (throw (ex-info "Image too large (max 5 MB)"
                                {:type :validation :reason :image-too-large})))
              {:content-type content-type :bytes bytes}))
          files)))

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
                                  (merge {:hostname (hu/tenant-hostname request)}
                                         (:query parameters))
                                  (:identity request)
                                  (authz/writer? request))))}
        :post {:summary    "Create a recipe"
               :responses  {200 {:body models.recipes/GetRecipeResponse}}
               :parameters {:body models.recipes/CreateRecipeRequest}
               :handler    (fn [{:keys [components parameters identity] :as request}]
                             (let [{:keys [content recipe-url] :as body} (:body parameters)]
                               (handle-write
                                #(recipes-api/create-recipe!
                                  (:database components)
                                  (merge body
                                         {:hostname   (hu/tenant-hostname request)
                                          :recipe-url (or (not-empty recipe-url)
                                                          (->slug (:title content)))
                                          :author     (or (:name identity) (:user-id identity))})))))}}]

   ["/scrape"
    {:post {:summary    "Fetch + extract a recipe draft from a URL (persists the raw scrape + processing run)"
            :responses  (merge hu/openapi-401
                               {200 {:body models.recipes/ScrapeResult}
                                422 {:body [:map [:reason :string]]}})
            :parameters {:body [:map [:url :string]]}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (let [url (get-in parameters [:body :url])
                                ctx {:database (:database components)
                                     :hostname (hu/tenant-hostname request)
                                     :api-key  (:api-key (:workflow-executor components))
                                     :fetcher  (:recipe-fetcher components)}]
                            ;; Expected scrape outcomes become a 422 the client
                            ;; can act on. Anything else — including a Firecrawl
                            ;; :render-failed — propagates to the Bugsnag
                            ;; exception-reporter middleware, which reports it.
                            (try
                              (ok (scraper/scrape-url ctx url))
                              (catch clojure.lang.ExceptionInfo e
                                (if (#{:fetch-failed :bot-blocked :no-recipe-found :blocked-url}
                                     (:reason (ex-data e)))
                                  (unprocessable-entity {:reason (name (:reason (ex-data e)))})
                                  (throw e))))))}}]

   ["/scrape-photo"
    {:post {:summary    "Extract a recipe draft from uploaded photo(s) (persists raw + processing run)"
            :responses  (merge hu/openapi-401
                               {200 {:body models.recipes/ScrapeResult}
                                400 {:body [:map [:reason :string]]}
                                422 {:body [:map [:reason :string]]}})
            :handler    (fn [{:keys [components params] :as request}]
                          (try
                            (let [images (multipart-images params)
                                  ctx    {:database    (:database components)
                                          :hostname    (hu/tenant-hostname request)
                                          :api-key     (:api-key (:workflow-executor components))
                                          :transcriber (:image-transcriber components)}]
                              (try
                                (ok (scraper/scrape-photo ctx images))
                                (catch clojure.lang.ExceptionInfo e
                                  (if (= :no-recipe-found (:reason (ex-data e)))
                                    (unprocessable-entity {:reason (name (:reason (ex-data e)))})
                                    (throw e)))))
                            (catch clojure.lang.ExceptionInfo e
                              (if (= :validation (:type (ex-data e)))
                                (bad-request {:reason (name (:reason (ex-data e)))})
                                (throw e)))))}}]

   ["/:recipe-url"
    {:get    {:summary    "Get a single recipe (access-checked)"
              :responses  (merge hu/openapi-404 {200 {:body models.recipes/GetRecipeResponse}})
              :parameters {:path {:recipe-url :string}}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (if-let [recipe (first (recipes-api/get-visible-recipes
                                                    (:database components)
                                                    {:hostname   (hu/tenant-hostname request)
                                                     :recipe-url (get-in parameters [:path :recipe-url])}
                                                    (:identity request)
                                                    (authz/writer? request)))]
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
                               (hu/tenant-hostname request)
                               (get-in parameters [:path :recipe-url])
                               (:body parameters))))}
     :delete {:summary    "Delete a recipe"
              :responses  {200 {:body [:any]}}
              :parameters {:path {:recipe-url :string}}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (recipes-api/delete-recipe! (:database components)
                                                        (hu/tenant-hostname request)
                                                        (get-in parameters [:path :recipe-url]))
                            (ok {:deleted (get-in parameters [:path :recipe-url])}))}}]

   ["/:recipe-url/lineage"
    {:get {:summary    "Import lineage for a recipe (writer-only): processing run + raw scrape"
           :responses  (merge hu/openapi-401 hu/openapi-404
                              {200 {:body models.recipes/RecipeLineage}})
           :parameters {:path  {:recipe-url :string}
                        :query [:map [:include-raw {:optional true} :boolean]]}
           :handler    (fn [{:keys [components parameters] :as request}]
                         ;; Writer-only: the response exposes raw HTML/transcripts
                         ;; and full LLM prompts. Non-writers get 404 (no leak).
                         (if-not (authz/writer? request)
                           (not-found {:reason "Missing"})
                           (if-let [lineage (recipes-api/get-recipe-lineage
                                             (:database components)
                                             (hu/tenant-hostname request)
                                             (get-in parameters [:path :recipe-url])
                                             (boolean (get-in parameters [:query :include-raw])))]
                             (ok lineage)
                             (not-found {:reason "Missing"}))))}}]

   ["/:recipe-url/timeline"
    {:post {:summary    "Regenerate the cook timeline from current content (writer-only)"
            :responses  (merge hu/openapi-401 hu/openapi-404
                               {200 {:body [:map [:timeline models.recipes/Timeline]]}
                                502 {:body [:map [:reason :string]]}})
            :parameters {:path {:recipe-url :string}}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (if-not (authz/writer? request)
                            (not-found {:reason "Missing"})
                            (let [db        (:database components)
                                  host      (hu/tenant-hostname request)
                                  url       (get-in parameters [:path :recipe-url])]
                              (if-let [recipe (recipes-api/get-recipe db host url)]
                                (try
                                  (let [stored   (:timeline recipe)
                                        timeline (timeline-api/generate!
                                                  {:generator         (:timeline-generator components)
                                                   :content           (:content recipe)
                                                   :stored            stored
                                                   :generator-version timeline-api/default-generator-version
                                                   :now               (str (java.time.Instant/now))})]
                                    (if (identical? timeline stored)
                                      (ok {:timeline timeline})
                                      (ok {:timeline (:timeline (recipes-api/save-timeline! db host url timeline))})))
                                  (catch clojure.lang.ExceptionInfo e
                                    (if (= :generation (:type (ex-data e)))
                                      (if (= 429 (:status (ex-data e)))
                                        (service-unavailable {:reason "rate-limited"})
                                        (bad-gateway {:reason "generation-failed"}))
                                      (throw e))))
                                (not-found {:reason "Missing"})))))}
     :put  {:summary    "Apply duration overrides and re-pack (writer-only; no LLM)"
            :responses  (merge hu/openapi-401 hu/openapi-404
                               {200 {:body [:map [:timeline models.recipes/Timeline]]}})
            :parameters {:path {:recipe-url :string}
                         :body models.recipes/TimelineOverridesRequest}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (if-not (authz/writer? request)
                            (not-found {:reason "Missing"})
                            (let [db     (:database components)
                                  host   (hu/tenant-hostname request)
                                  url    (get-in parameters [:path :recipe-url])
                                  recipe (recipes-api/get-recipe db host url)]
                              (if-let [stored (:timeline recipe)]
                                (let [updated (timeline-api/with-overrides
                                               stored (get-in parameters [:body :overrides]))]
                                  (ok {:timeline (:timeline (recipes-api/save-timeline! db host url updated))}))
                                (not-found {:reason "Missing"})))))}}]])

(def reitit-recipe-labels-routes
  ["/recipe-labels" {:tags     ["recipe-labels"]
                     :security [{:andrewslai-pkce ["roles" "profile"]}]}
   ["" {:get  {:summary  "All labels for the tenant, each with its group name"
               :responses {200 {:body [:sequential models.recipes/RecipeLabel]}}
               :handler  (fn [{:keys [components] :as request}]
                           (ok (recipes-api/get-labels (:database components)
                                                       {:hostname (hu/tenant-hostname request)})))}
        :post {:summary    "Create a label (optionally in a group)"
               :responses  {200 {:body models.recipes/RecipeLabel}}
               :parameters {:body [:map
                                   [:name :string]
                                   [:group-id {:optional true} [:maybe :uuid]]]}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (recipes-api/create-label!
                                  (:database components)
                                  (assoc (:body parameters) :hostname (hu/tenant-hostname request)))))}}]
   ["/:id" {:put    {:summary    "Rename a label"
                     :parameters {:path {:id :uuid}
                                  :body [:map [:name :string]]}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (ok (recipes-api/rename-label!
                                        (:database components)
                                        (get-in parameters [:path :id])
                                        (hu/tenant-hostname request)
                                        (get-in parameters [:body :name]))))}
            :delete {:summary    "Delete a label"
                     :parameters {:path {:id :uuid}}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (recipes-api/delete-label! (:database components)
                                                              (get-in parameters [:path :id])
                                                              (hu/tenant-hostname request))
                                   (ok {:deleted (get-in parameters [:path :id])}))}}]])

(def reitit-recipe-label-groups-routes
  ["/recipe-label-groups" {:tags     ["recipe-labels"]
                           :security [{:andrewslai-pkce ["roles" "profile"]}]}
   ["" {:get  {:summary   "All label groups for the tenant"
               :responses {200 {:body [:any]}}
               :handler   (fn [{:keys [components] :as request}]
                            (ok (recipes-api/get-label-groups (:database components)
                                                              {:hostname (hu/tenant-hostname request)})))}
        :post {:summary    "Create a label group"
               :parameters {:body [:map [:name :string]]}
               :responses  {200 {:body [:any]}}
               :handler    (fn [{:keys [components parameters] :as request}]
                             (ok (recipes-api/create-label-group!
                                  (:database components)
                                  (assoc (:body parameters) :hostname (hu/tenant-hostname request)))))}}]
   ["/:id" {:put    {:summary    "Rename a label group"
                     :parameters {:path {:id :uuid}
                                  :body [:map [:name :string]]}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (ok (recipes-api/rename-label-group!
                                        (:database components)
                                        (get-in parameters [:path :id])
                                        (hu/tenant-hostname request)
                                        (get-in parameters [:body :name]))))}
            :delete {:summary    "Delete a label group (cascades to its labels)"
                     :parameters {:path {:id :uuid}}
                     :responses  {200 {:body [:any]}}
                     :handler    (fn [{:keys [components parameters] :as request}]
                                   (recipes-api/delete-label-group! (:database components)
                                                                    (get-in parameters [:path :id])
                                                                    (hu/tenant-hostname request))
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
                                             (merge {:hostname (hu/tenant-hostname request)}
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
                                   {:id recipe-id :hostname (hu/tenant-hostname request)}
                                   {:id group-id}))))}}]
   ["/:audience-id" {:delete {:summary    "Unshare"
                              :parameters {:path {:audience-id :uuid}}
                              :responses  {200 {:body [:any]}}
                              :handler    (fn [{:keys [components parameters] :as _request}]
                                            (ok (recipes-api/delete-recipe-audience!
                                                 (:database components)
                                                 (get-in parameters [:path :audience-id]))))}}]])
