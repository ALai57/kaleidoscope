(ns andrewslai.clj.api.photos
  (:require [andrewslai.clj.entities.photo :as photo]))

(def get-all-photos photo/get-all-photos)
(def create-photo! photo/create-photo!)
(def get-photo photo/get-photo)
