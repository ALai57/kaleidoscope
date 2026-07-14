(ns kaleidoscope.timeline.mock
  (:require [clojure.string :as str]
            [kaleidoscope.api.recipe-timeline :as tl]
            [kaleidoscope.timeline.protocol :as protocol]))

(def ^:private passive-cue
  #"(?i)marinate|marinade|rise|proof|chill|rest|refrigerate|soak|bake|roast|simmer|freeze")

(defrecord MockGenerator []
  protocol/ITimelineGenerator
  (segment [_this recipe _changed-ids _cached]
    (let [sections (get-in recipe [:content :sections])
          n        (count sections)]
      {:components
       (vec (map-indexed
             (fn [i section]
               (let [cid   (tl/component-id section i)
                     steps (:steps section)
                     kind  (if (some #(re-find passive-cue %) steps) "passive" "active")
                     ;; last component depends on the single phase of every earlier one
                     deps  (if (= i (dec n))
                             (vec (for [j (range (dec n))]
                                    (let [pid (tl/component-id (nth sections j) j)]
                                      (str pid "/" pid))))
                             [])]
                 {:name cid
                  :phases [{:id       (str cid "/" cid)
                            :label    cid
                            :kind     kind
                            :steps    (vec (range (count steps)))
                            :estimate (+ 2 (* 3 (count steps)))
                            :deps     deps}]}))
             sections))})))

(defn make-mock-generator [] (->MockGenerator))
