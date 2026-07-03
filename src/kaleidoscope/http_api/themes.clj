(ns kaleidoscope.http-api.themes
  (:require [kaleidoscope.api.themes :as themes-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [no-content ok not-found]]
            [taoensso.timbre :as log]))

(def ThemeRequest
  [:map
   [:display-name :string]
   [:config :map]])

(def example-theme-request
  {:display-name "My Theme"
   :config       {:primary {:main "#ABC123"}}})

(def reitit-themes-routes
  ["/themes" {:tags ["themes"]
              ;; For testing only - this is a mechanism to always get results from a particular
              ;; host URL.
              ;;
              ;;:host      "andrewslai.localhost"
              }
   ["" {:get {:summary    "Retrieve all themes"
              :responses  {200 {:description "A collection of themes"
                                :content     {"application/json"
                                              {:schema [:sequential themes-api/Theme]}}}}
              :parameters {:query :any}
              :handler    (fn [{:keys [components parameters] :as request}]
                            (log/infof "Received params %s" parameters)
                            (let [query-params (:query parameters)
                                  themes       (themes-api/get-themes (:database components) query-params)]
                              (if (empty? themes)
                                (not-found)
                                (ok themes))))}

        :post {:summary   "Create a theme"
               :openapi   {:security [{:andrewslai-pkce ["roles" "profile"]}]}
               :responses (merge hu/openapi-401
                                 {200 {:description "The theme that was created"
                                       :content     {"application/json"
                                                     {:schema themes-api/Theme}}}})
               :request   {:description "The theme you want to create"
                           :content     {"application/json"
                                         {:schema   [:any]
                                          :examples {"example-theme-1" {:summary "Example theme 1"
                                                                        :value   example-theme-request}}}}}
               :handler   (fn [{:keys [components parameters] :as request}]
                            (try
                              (log/info "Creating theme!" parameters)
                              (let [theme    (merge (:request parameters)
                                                    {:hostname (hu/get-host request)
                                                     :owner-id (:user-id (:identity request))})
                                    [result] (themes-api/create-theme! (:database components) theme)]
                                (log/infof "Created theme %s" result)
                                (ok result))
                              (catch Exception e
                                (log/error "Caught exception " e))))}}]
   ["/:theme-id" {:delete {:summary    "Delete a theme"
                           :responses  (merge hu/openapi-401
                                              hu/openapi-404
                                              {200 {:description "Success deleting the theme"
                                                    :content     {"application/json"
                                                                  {:schema [:any]}}}})
                           :parameters {:path {:theme-id :uuid}}
                           :handler    (fn [{:keys [components parameters] :as request}]
                                         (try
                                           (let [{:keys [theme-id]} (:path parameters)
                                                 requester-id       (:user-id (:identity request))
                                                 site               (hu/get-host request)]
                                             (log/infof "User %s attempting to delete theme %s!" requester-id theme-id)
                                             (if-let [result (themes-api/delete-theme! (:database components) requester-id site theme-id)]
                                               (no-content)
                                               (not-found {:reason "Theme not found"})))
                                           (catch Exception e
                                             (log/error "Caught exception " e))))}

                  :put {:summary    "Save a theme"
                        :openapi    {:security [{:andrewslai-pkce ["roles" "profile"]}]}
                        :responses  (merge hu/openapi-401
                                           hu/openapi-404
                                           {200 {:description "The theme that was created"
                                                 :content     {"application/json"
                                                               {:schema themes-api/Theme}}}})
                        :request    {:description "The theme you want to create"
                                     :content     {"application/json"
                                                   {:schema   [:any]
                                                    :examples {"example-theme-1" {:summary "Example theme 1"
                                                                                  :value   example-theme-request}}}}}
                        :parameters {:path {:theme-id :uuid}}
                        :handler    (fn [{:keys [components parameters] :as request}]
                                      (try
                                        (log/info "Updating theme!" parameters)
                                        (let [{:keys [theme-id]} (:path parameters)
                                              requester-id       (:user-id (:identity request))
                                              site               (hu/get-host request)
                                              theme              (merge (:request parameters)
                                                                        {:hostname site
                                                                         :owner-id requester-id
                                                                         :id       theme-id})]
                                          (if-let [[result] (themes-api/update-theme! (:database components) requester-id site theme)]
                                            (do
                                              (log/infof "Updated theme %s" result)
                                              (ok result))
                                            (not-found {:reason "Theme not found"})))
                                        (catch Exception e
                                          (log/error "Caught exception " e))))}}]])
