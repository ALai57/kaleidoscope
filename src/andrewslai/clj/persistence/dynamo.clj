(ns andrewslai.clj.persistence.dynamo
  (:require [amazonica.aws.dynamodbv2 :as ddb]))

(comment
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Some helpful utilities
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (ddb/list-tables)

  (ddb/scan :table-name "images")

  (ddb/describe-table :table-name "images"))

(comment
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Put a single item into the bucket
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (ddb/put-item :table-name                     "images"
                :return-consumed-capacity       "TOTAL"
                :return-item-collection-metrics "SIZE"
                :item {:photo_name "/media/example"
                       :sort_key   "ALBUM#something"}))

(comment
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Insert lots of items
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (defn make-items
    "Just save the metadata"
    [path]
    [{:photo_name path :sort_key "ALBUM#something"}
     {:photo_name path :sort_key "ALBUM#other"}
     {:photo_name path :sort_key "ALBUM#foo"}])

  (def ITEMS
    (mapcat make-items
            ["/media/example1" "/media/example2" "/media/example3"]))

  (ddb/batch-write-item
   :return-consumed-capacity       "TOTAL"
   :return-item-collection-metrics "SIZE"
   :request-items {"images"
                   (vec (for [item ITEMS]
                          {:put-request
                           {:item item}}))})
  )

(comment
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Get Albums
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (ddb/query
   :table-name "images"
   :index-name "sort_key_idx"
   ;;:key-conditions {:sort_key {:attribute-value-list ["ALBUM#"] :comparison-operator "BEGINS_WITH"}}
   )

  )

(comment
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Get by Album
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (ddb/query
   :table-name "images"
   :index-name "sort_key_idx"
   :key-conditions {:sort_key {:attribute-value-list ["ALBUM#foo"] :comparison-operator "EQ"}})

  )

(comment
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Remove image from album
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (ddb/delete-item
   :table-name "images"
   :key {:photo_name "/media/example1"
         :sort_key "ALBUM#foo"})

  )
