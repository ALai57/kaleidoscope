(ns kaleidoscope.models.albums
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

;; TODO: Add json decode to/from timestamp
(def Album
  [:map
   [:id :uuid]
   [:album-name :string]
   [:created-at  inst?]
   [:modified-at inst?]])

(def example-album
  {:album-name     "hello"
   :description    "first album"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-3d282833a27d"
   :created-at     "2020-10-28T02:55:27Z",
   :modified-at    "2020-10-28T02:55:27Z",})

(def example-album-2
  {:album-name     "bye"
   :description    "secondalbum"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-999999999999"
   :created-at     "2022-10-01T02:55:27Z",
   :modified-at    "2022-10-01T02:55:27Z",})

(def AlbumContent
  [:map
   [:album-id :uuid]
   [:album-content-id :uuid]
   [:added-to-album-at inst?]
   [:photo-id :uuid]
   [:photo-src :string]
   [:photo-title :string]
   [:album-name :string]
   [:album-description :string]
   [:cover-photo-id :uuid]
   [:cover-photo-src :string]])

(comment
  ;; Example data for article spec
  (require '[malli.core :as m])
  (require '[malli.generator :as mg])

  ;; Currently no generator
  (mg/generate Album)
  )
