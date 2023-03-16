(ns kaleidoscope.clj.http-api.album
  (:require [kaleidoscope.clj.api.albums :as albums-api]
            [kaleidoscope.clj.utils.core :as utils]
            [kaleidoscope.cljc.specs.albums] ;; Install specs
            [compojure.api.sweet :refer [context DELETE GET POST PUT]]
            [ring.util.http-response :refer [no-content not-found! ok]]
            [taoensso.timbre :as log]))

(def album-routes
  (context "/albums" []
    :coercion   :spec
    :components [database]
    :tags       ["albums"]

    (GET "/" []
      :swagger {:summary   "Retrieve all albums"
                :produces  #{"application/json"}
                :responses {200 {:description "A collection of all albums"
                                 :schema      :andrewslai.albums/albums}}}
      (log/info "Getting albums")
      (ok (albums-api/get-albums database)))

    (GET "/-/contents" []
      :tags    ["album contents"]
      :swagger {:summary   "Retrieve contents from all albums"
                :produces  #{"application/json"}
                :responses {200 {:description "All album contents"
                                 :schema      :andrewslai.albums.contents/album-contents}}}
      (log/info "Getting contents")
      (ok (albums-api/get-album-contents database)))

    (POST "/" {params :params}
      :swagger {:summary   "Add an album"
                :consumes  #{"application/json"}
                :produces  #{"application/json"}
                :request   :andrewslai.albums/album
                :responses {200 {:description "Success!"
                                 :schema      :andrewslai.albums/album}}}
      (log/info "Creating album" params)
      (ok (first (albums-api/create-album! database params))))

    (context "/:id" [id]
      (GET "/" []
        :swagger {:summary   "Retrieve album by ID"
                  :produces  #{"application/json"}
                  :responses {200 {:description "An album"
                                   :schema      :andrewslai.albums/album}}}
        (log/infof "Getting album: %s" id)
        (ok (first (albums-api/get-albums database {:id id}))))

      (PUT "/" {params :params}
        :swagger {:summary   "Update an album"
                  :consumes  #{"application/json"}
                  :produces  #{"application/json"}
                  :responses {200 {:description "An album"
                                   :schema      :andrewslai.albums/album}}}
        (log/infof "Updating album: %s with: %s" id params)
        (ok (first (albums-api/update-album! database params))))

      (context "/contents" []
        (GET "/" []
          :tags    ["album contents"]
          :swagger {:summary   "Retrieve an album's contents"
                    :produces  #{"application/json"}
                    :responses {200 {:description "An album"
                                     :schema      :andrewslai.albums.contents/album-contents}}}
          (log/infof "Getting album contents from album: %s" id)
          (ok (albums-api/get-album-contents database {:album-id id})))

        (DELETE "/" {params :body-params}
          :tags    ["album contents"]
          :swagger {:summary   "Delete an album's contents"
                    :produces  #{"application/json"}
                    :responses {200 {:description "An album"
                                     :schema      :andrewslai.albums.contents/album-content}}}
          (let [content-ids (map :id params)]
            (log/infof "Removing contents %s from album %s" content-ids id)
            (albums-api/remove-content-album-link! database content-ids)
            (no-content)))

        ;; Must use body params because POST is accepting a JSON array
        (POST "/" {params :body-params :as req}
          :tags    ["album contents"]
          :swagger {:summary     "Add content to album"
                    :description "Supports bulk insert."
                    :consumes    #{"application/json"}
                    :produces    #{"application/json"}
                    :responses   {200 {:description "An album"
                                       :schema      :andrewslai.albums.contents/album-contents}}}
          (let [photo-ids (map :id params)]
            (log/infof "Adding photo: %s to album: %s" photo-ids id)
            (ok (albums-api/add-photos-to-album! database id photo-ids))))

        (context "/:content-id" [content-id]
          (GET "/" []
            :tags    ["album contents"]
            :swagger {:summary   "Retrieve content from album by ID"
                      :produces  #{"application/json"}
                      :responses {200 {:description "An album"
                                       :schema      :andrewslai.albums.contents/album-content}}}
            (log/infof "Getting album content %s for album: %s" content-id id)
            (let [[result] (albums-api/get-album-contents database {:album-id         id
                                                                    :album-content-id content-id})]
              (if result
                (ok result)
                (not-found!))))

          (DELETE "/" []
            :tags    ["album contents"]
            :swagger {:summary   "Remove content from an album"
                      :produces  #{"application/json"}
                      :responses {200 {:description "An album"
                                       :schema      :andrewslai.albums.contents/album-content}}}
            (log/infof "Removing content: %s from album: %s" content-id id)
            (albums-api/remove-content-album-link! database content-id)
            (no-content)))
        ))))
