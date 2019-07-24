(ns andrewslai.cljs.circle-nav
  (:require [re-frame.core :refer [dispatch]]
            [clojure.string :as str]))

;; Add images around circle
;; Add fly-in behavior
;; Add behavior on click

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Setup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def nav-images-path "images/nav-bar/")

(def image-set
  (repeat 6 (str nav-images-path "andrew-head-icon.svg")))

(def n-icons (count image-set))
(def radius 100)
(def center-x 300)
(def center-y 400)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rad-per-item [n-items]
  (/ (* 2 Math/PI) n-items))
(defn angle [item-number]
  (* (rad-per-item n-icons) item-number))

(defn x [item-number]
  (->> item-number
       angle
       Math/cos
       (* radius)
       (+ center-x)))

(defn y [item-number]
  (->> item-number
       angle
       Math/sin
       (* radius)
       (+ center-y)))

(defn calc-circle-positions [i v]
  [:img
   {:src v
    :style {:top (str (x i) "px")
            :left (str (y i) "px")
            :position :absolute
            :background "blue"
            :width "50px"
            :animation "myOrbit 3s infinite alternate"}}])
;;go-left-right

(defn render-icons []
  (let [posns (map-indexed calc-circle-positions image-set)]
    ))

(defn circle-nav []
  [:div#circle-nav
   [:p "Hello!"]
   (map-indexed calc-circle-positions image-set)
   ])

(comment
  (repeat 5 image-set)
  (x 1)
  (y 4)
  (map-indexed calc-circle-positions image-set)
  )

