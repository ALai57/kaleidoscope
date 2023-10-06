(ns kaleidoscope.http-api.album
  (:require [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.models.albums :as models.albums] ;; Install specs
            [compojure.api.sweet :refer [context DELETE GET POST PUT]]
            [ring.util.http-response :refer [no-content not-found! ok]]
            [taoensso.timbre :as log]
            [kaleidoscope.http-api.http-utils :as hu]))

(def CreateAlbumRequest
  [:map
   [:album-name :string]
   [:cover-photo-id {:optional true} :uuid]
   [:description {:optional true} :string]
   [:created-at  inst?]
   [:modified-at inst?]])

(def example-album-request
  {:album-name     "hello"
   :description    "first album"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-3d282833a27d"
   :created-at     "2020-10-28T02:55:27Z",
   :modified-at    "2020-10-28T02:55:27Z",})

(def reitit-albums-routes
  ["/albums" {:tags    ["albums"]
              :openapi {:security [{:andrewslai-pkce ["roles" "profile"]}]}
              ;; For testing only - this is a mechanism to always get results from a particular
              ;; host URL.
              ;;
              ;;:host      "andrewslai.localhost"
              }
   ["" {:get {:summary   "Retrieve all albums"
              :responses {200 {:description "A collection of all albums"
                               :content     {"application/json"
                                             {:schema   [:sequential models.albums/Album]
                                              :examples {"example-albums"
                                                         {:summary "Example albums"
                                                          :value   [models.albums/example-album models.albums/example-album-2]}}}}}}

              :handler (fn [{:keys [components] :as request}]
                         (log/info "Getting albums")
                         (ok (albums-api/get-albums (:database components))))}
        :post {:summary   "Add an album"
               :responses {200 {:description "The group that was created"
                                :content     {"application/json"
                                              {:schema   models.albums/Album
                                               :examples {"example-albums"
                                                          {:summary "Created album"
                                                           :value   models.albums/example-album}}}}}}
               :request   {:description "Album"
                           :content     {"application/json"
                                         {:schema   CreateAlbumRequest
                                          :examples {"example-album-1" {:summary "Example album 1"
                                                                        :value   example-album-request}}}}}
               :handler   (fn [{:keys [components body-params] :as request}]
                            (log/info "Creating album" body-params)
                            (ok (first (albums-api/create-album! (:database components) body-params))))}}]

   ["/-/contents" {:conflicting true
                   :get         {:summary   "Retrieve contents from all albums"
                                 :responses {200 {:description "The group that was created"
                                                  :content     {"application/json"
                                                                {:schema [:sequential models.albums/AlbumContent]}}}}
                                 :handler   (fn [{:keys [components] :as request}]
                                              (log/info "Getting contents")
                                              (ok (albums-api/get-album-contents (:database components))))}}]
   ["/:album-id" {:get {:summary    "Retrieve album by ID"
                        :responses  (merge hu/openapi-404
                                           {200 {:description "An album"
                                                 :content     {"application/json"
                                                               {:schema models.albums/Album}}}})
                        :parameters {:path {:album-id string?}}
                        :handler    (fn [{:keys [components body-params path-params] :as request}]
                                      (let [{:keys [album-id]} path-params]
                                        (log/infof "Getting album: %s" album-id)
                                        (ok (first (albums-api/get-albums (:database components) {:id album-id})))))}
                  :put {:summary    "Update an album"
                        :responses  (merge hu/openapi-401
                                           {200 {:description "An album"
                                                 :content     {"application/json"
                                                               {:schema models.albums/Album}}}})
                        :parameters {:path {:album-id string?}}
                        :handler    (fn [{:keys [components body-params path-params] :as request}]
                                      (let [{:keys [album-id]} path-params]
                                        (log/infof "Updating album: %s with: %s" album-id body-params)
                                        (ok (first (albums-api/update-album! (:database components)
                                                                             (assoc body-params :id album-id))))))}}]
   ["/:album-id/contents" {:conflicting true
                           :get    {:summary    "Retrieve an album's contents"
                                    :responses  (merge hu/openapi-404
                                                       {200 {:description "An album's contents"
                                                             :content     {"application/json"
                                                                           {:schema models.albums/AlbumContent}}}})
                                    :parameters {:path {:album-id string?}}
                                    :handler    (fn [{:keys [components body-params path-params] :as request}]
                                                  (let [{:keys [album-id]} path-params]
                                                    (log/infof "Getting album contents from album: %s" album-id)
                                                    (ok (albums-api/get-album-contents (:database components) {:album-id album-id}))))}
                           :post   {:summary     "Add content to album"
                                    :description "Supports bulk insert."
                                    :responses   (merge hu/openapi-401
                                                        {200 {:description "An album's contents"
                                                              :content     {"application/json"
                                                                            {:schema [:sequential models.albums/AlbumContent]}}}})
                                    :request     {:description "Contents to add"
                                                  :content     {"application/json"
                                                                {:schema   [:sequential [:map [:id :uuid]]]
                                                                 :examples {"example-album-1" {:summary "Example album 1"
                                                                                               :value   [{:id #uuid "a52a7e7e-89ca-4fbf-9062-e8089254d0e5"}]}}}}}
                                    :parameters  {:path {:album-id string?}}
                                    :handler     (fn [{:keys [components body-params path-params] :as request}]
                                                   (let [{:keys [album-id]} path-params
                                                         photo-ids          (map :id body-params)]
                                                     (log/infof "Adding photo: %s to album: %s" photo-ids album-id)
                                                     (ok (albums-api/add-photos-to-album! (:database components) album-id photo-ids))))}
                           :delete {:summary    "Delete an album's contents"
                                    :responses  (merge hu/openapi-401
                                                       {200 {:description "An album"
                                                             :content     {"application/json"
                                                                           {:schema models.albums/Album}}}})
                                    :parameters {:path {:album-id string?}}
                                    :handler    (fn [{:keys [components body-params path-params] :as request}]
                                                  ;; This would allow a user to delete contents from an album that is different from the path specified
                                                  (let [{:keys [album-id]} path-params
                                                        content-ids        (map :id body-params)]
                                                    (log/infof "Removing contents %s from album %s" content-ids album-id)
                                                    (albums-api/remove-content-album-link! (:database components) content-ids)
                                                    (no-content)))}}]
   ["/:album-id/contents/:content-id"
    {:get {:summary    "Retrieve content from album by ID"
           :responses  (merge hu/openapi-404
                              {200 {:description "An album's contents"
                                    :content     {"application/json"
                                                  {:schema models.albums/AlbumContent}}}})
           :parameters {:path {:album-id   string?
                               :content-id string?}}
           :handler    (fn [{:keys [components path-params] :as request}]
                         (let [{:keys [album-id content-id]} path-params

                               _        (log/infof "Getting album content %s for album: %s" content-id album-id)
                               [result] (albums-api/get-album-contents (:database components)
                                                                       {:album-id         album-id
                                                                        :album-content-id content-id})]
                           (if result
                             (ok result)
                             (not-found!))))}
     :delete {:summary    "Remove content from an album"
              :responses  (merge hu/openapi-401
                                 {200 {:description "An album"
                                       :content     {"application/json"
                                                     {:schema models.albums/AlbumContent}}}})
              :parameters {:path {:album-id   string?
                                  :content-id string?}}
              :handler    (fn [{:keys [components path-params] :as request}]
                            ;; This would allow a user to delete contents from an album that is different from the path specified
                            (let [{:keys [content-id album-id]} path-params]
                              (log/infof "Removing content: %s from album: %s" content-id album-id)
                              (albums-api/remove-content-album-link! (:database components) content-id)
                              (no-content)))}}]])
