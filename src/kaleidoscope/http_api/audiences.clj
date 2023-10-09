(ns kaleidoscope.http-api.audiences
  (:require [compojure.api.sweet :refer [context GET PUT DELETE]]
            [kaleidoscope.api.audiences :as audiences-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [ok not-found]]))

(def reitit-audiences-routes
  ["/article-audiences" {:tags     ["audiences"]
                         :security [{:andrewslai-pkce ["roles" "profile"]}]
                         ;; For testing only - this is a mechanism to always get results from a particular
                         ;; host URL.
                         ;;
                         ;;:host      "andrewslai.localhost"
                         }
   ["" {:get {:summary   "Retrieve all audiences"
              :responses (merge hu/openapi-401
                                {200 {:description "A collection of all audiences"
                                      :content     {"application/json"
                                                    {:schema [:any]}}}})

              :handler (fn [{:keys [components] :as request}]
                         (let [audiences (->> {:hostname (hu/get-host request)}
                                              (audiences-api/get-article-audiences (:database components)))]
                           (if (empty? audiences)
                             (not-found)
                             (ok audiences))))}
        :put {:summary   "A collection of all audiences"
              :responses (merge hu/openapi-401
                                {200 {:description "The group that was created"
                                      :content     {"application/json"
                                                    {:schema [:any]}}}})
              :request   {:description "A create audience requestj"
                          :content     {"application/json"
                                        {:schema [:map
                                                  [:article-id :int]
                                                  [:group-id :uuid]]}}}
              :handler   (fn [{:keys [components parameters] :as request}]
                           (let [{:keys [article-id group-id]} (:request parameters)]
                             (ok (audiences-api/add-audience-to-article! (:database components)
                                                                         {:id       article-id
                                                                          :hostname (hu/get-host request)}
                                                                         {:id group-id}))))}}]

   ["/:audience-id" {:delete {:summary    "Delete an audience"
                              :responses  (merge hu/openapi-401
                                                 {200 {:description "Success deleting the group"
                                                       :content     {"application/json"
                                                                     {:schema [:any]}}}})
                              :parameters {:path {:audience-id string?}}
                              :handler    (fn [{:keys [components parameters] :as request}]
                                            (let [{:keys [audience-id]} (:path parameters)]
                                              (ok (audiences-api/delete-article-audience! (:database components) audience-id))))}}]])
