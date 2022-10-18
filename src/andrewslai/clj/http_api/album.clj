(ns andrewslai.clj.http-api.album
  (:require [andrewslai.clj.api.albums :as albums-api]
            [compojure.api.sweet :refer [context defroutes DELETE GET POST PUT]]
            [ring.util.http-response :refer [no-content not-found! ok]]
            [taoensso.timbre :as log]))

(defroutes album-routes
  (context "/albums" []
    :components [database]

    (GET "/" []
      :swagger {:summary     "Retrieve all albums"
                :description (str "This endpoint retrieves all albums. "
                                  "The endpoint is currently not paginated")
                :produces    #{"application/json"}
                :responses   {200 {:description "A collection of all albums"
                                   :schema      :andrewslai.albums/albums}}}
      (log/info "Getting albums")
      (ok (albums-api/get-albums database)))

    (GET "/-/contents" []
      :swagger {:summary     "Retrieve contents from all albums"
                :description (str "This endpoint retrieves the contents of all albums"
                                  "The endpoint is currently not paginated")
                :produces    #{"application/json"}
                :responses   {200 {:description "A collection of all albums"
                                   :schema      :andrewslai.albums/albums}}}
      (log/info "Getting contents")
      (ok (albums-api/get-album-contents database)))

    (POST "/" {params :params}
      :swagger {:summary     "Add an album"
                :description "This endpoint inserts an album into the database"
                :consumes    #{"application/json"}
                :produces    #{"application/json"}
                :request     :andrewslai.albums-api/album
                :responses   {200 {:description "Success!"
                                   :schema      :andrewslai.albums/album}}}
      (log/info "Creating album" params)
      (let [now (java.time.LocalDateTime/now)]
        (ok (first (albums-api/create-album! database (assoc params
                                                             :created-at now
                                                             :modified-at now))))))

    (context "/:id" [id]
      (GET "/" []
        :swagger {:summary     "Retrieve an album"
                  :description "This endpoint retrieves an album by ID"
                  :produces    #{"application/json"}
                  :responses   {200 {:description "An album"
                                     :schema      :andrewslai.albums/album}}}
        (log/infof "Getting album: %s" id)
        (ok (first (albums-api/get-albums database {:id id}))))

      (PUT "/" {params :params}
        :swagger {:summary     "Update an album"
                  :description "This endpoint updates an album"
                  :produces    #{"application/json"}
                  :responses   {200 {:description "An album"
                                     :schema      :andrewslai.albums/album}}}
        (log/infof "Updating album: %s with: %s" id params)
        (ok (first (albums-api/update-album! database params))))

      (context "/contents" []
        (GET "/" []
          :swagger {:summary     "Retrieve an album's contents"
                    :description "This endpoint retrieves an album's contents"
                    :produces    #{"application/json"}
                    :responses   {200 {:description "An album"
                                       :schema      :andrewslai.albums/album}}}
          (log/infof "Getting album contents from album: %s" id)
          (ok (albums-api/get-album-contents database {:album-id id})))

        (DELETE "/" {params :body-params}
          :swagger {:summary     "Delete an album's contents"
                    :description "This endpoint removes contents from an album. Supports bulk delete."
                    :produces    #{"application/json"}
                    :responses   {200 {:description "An album"
                                       :schema      :andrewslai.albums/album}}}
          (let [content-ids (map :id params)]
            (log/infof "Removing contents %s from album %s" content-ids id)
            (albums-api/remove-content-album-link! database content-ids)
            (no-content)))

        ;; Must use body params because POST is accepting a JSON array
        (POST "/" {params :body-params :as req}
          :swagger {:summary     "Add contents to album"
                    :description "This endpoint adds to album's contents. Supports bulk insert."
                    :produces    #{"application/json"}
                    :responses   {200 {:description "An album"
                                       :schema      :andrewslai.albums/album}}}
          (let [photo-ids (map :id params)]
            (log/infof "Adding photo: %s to album: %s" photo-ids id)
            (ok (albums-api/add-photos-to-album! database id photo-ids))))

        (context "/:content-id" [content-id]
          (GET "/" []
            :swagger {:summary     "Retrieve one of the album's contents"
                      :description "This endpoint retrieves an single piece of the album's content"
                      :produces    #{"application/json"}
                      :responses   {200 {:description "An album"
                                         :schema      :andrewslai.albums/album}}}
            (log/infof "Getting album content %s for album: %s" content-id id)
            (let [[result] (albums-api/get-album-contents database {:album-id   id
                                                                    :album-content-id content-id})]
              (if result
                (ok result)
                (not-found!))))

          (DELETE "/" []
            :swagger {:summary     "Remove content from an album"
                      :description "This endpoint removes something from an album"
                      :produces    #{"application/json"}
                      :responses   {200 {:description "An album"
                                         :schema      :andrewslai.albums/album}}}
            (log/infof "Removing content: %s from album: %s" content-id id)
            (albums-api/remove-content-album-link! database content-id)
            (no-content)))
        ))))
