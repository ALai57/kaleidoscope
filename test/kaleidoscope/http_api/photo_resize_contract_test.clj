(ns kaleidoscope.http-api.photo-resize-contract-test
  "CI guards for the Phase-1 linchpin (PLAN.md §8): the resizer follows whatever
  bucket the notify URL names, so the notify message shape and the
  write-location<->put-file equality are pinned here. A commit that changes
  either turns this red — the gallery cannot silently go blank weeks later."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is are testing]]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.http-api.photo :as photo]
            [kaleidoscope.http-api.tenant :as tenant]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as memory]
            [kaleidoscope.persistence.filesystem.read-through :as rt]
            [kaleidoscope.persistence.filesystem.s3-impl :as s3]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.utils.core :as u]))

(defn- ->file
  [path content]
  (memory/file {:name    (last (string/split path #"/"))
                :content (java.io.ByteArrayInputStream. (.getBytes (str content)))
                :version "v1"
                :metadata {}}))

(defn mem
  "In-memory store from a flat {path -> content} map (nested the way MemFS stores)."
  [contents]
  (memory/make-mem-fs {:store (atom (reduce-kv (fn [store path content]
                                                 (assoc-in store (string/split path #"/") (->file path content)))
                                               {}
                                               contents))}))

(defn tmp
  "A temp File carrying `content`, as an upload's :tempfile."
  [content]
  (let [f (java.io.File/createTempFile "photo" ".bin")]
    (spit f content)
    (.deleteOnExit f)
    f))

(deftest write-location-matches-the-put-target
  ;; put-file writes to (:bucket, (prefixed-key :prefix raw-key)); the resizer URL
  ;; is built from write-location. They MUST be equal or the resizer 404s and
  ;; renditions vanish silently.
  (are [store expected] (= expected (fs/write-location store "media/abc/raw.jpg"))
    (s3/make-s3 {:bucket "kal-media-prod"})
    {:bucket "kal-media-prod" :key "media/abc/raw.jpg"}                 ;; plain, no prefix

    (s3/make-s3 {:bucket "kal-ephemeral" :prefix "eph-x/"})
    {:bucket "kal-ephemeral" :key "eph-x/media/abc/raw.jpg"}            ;; prefix folded into the key

    (rt/->ReadThroughFS (s3/make-s3 {:bucket "kal-eph-x-media"}) [])
    {:bucket "kal-eph-x-media" :key "media/abc/raw.jpg"}))              ;; delegates to the writer

(deftest serve-and-upload-unchanged-when-media-store-absent
  ;; The load-bearing regression: prod runs this build with MEDIA_BUCKET unset,
  ;; so no media store is registered. Serve resolves via :asset-store and upload
  ;; writes through that same per-tenant adapter — behavior identical to before.
  (let [db       (embedded-h2/fresh-db!)
        photo-id (u/uuid)
        _        (albums-api/create-photo! db {:id photo-id :hostname "andrewslai.com"})
        _        (albums-api/create-photo-version! db {:photo-id       photo-id
                                                       :hostname       "andrewslai.com"
                                                       :path           "media/abc/raw.jpg"
                                                       :filename       "raw.jpg"
                                                       :image-category "raw"})
        store    (mem {"media/abc/raw.jpg" "IMG"})               ;; the per-tenant asset-store adapter
        adapters {"andrewslai.com" store}                        ;; NO tenant/media-store key
        base     {:tenant     {:hostname "andrewslai.com" :tenant-name "andrewslai.com" :asset-store "andrewslai.com"}
                  :components {:static-content-adapters adapters
                               :database                db
                               :notify-image-resizer!   (fn [& _] nil)}}]

    (testing "serve resolves via :asset-store, not the (absent) media store"
      (let [resp (photo/serve-photo (assoc base :parameters {:path {:photo-id photo-id :filename "raw.jpg"}}))]
        (is (= 200 (:status resp)))))

    (testing "upload writes through the per-tenant asset-store adapter"
      (let [{:keys [photo-id]} (photo/process-photo-upload! base {:filename "x.jpg" :tempfile (tmp "NEW")})]
        (is (not (fs/does-not-exist?
                  (fs/get-file store (format "media/%s/raw.jpg" photo-id) {}))))))))

(deftest resize-notify-message-is-exactly-the-write-location-url
  ;; The deployed resizer reads ONLY the s3://bucket/key in the message body.
  ;; This test IS the contract — break the shape here, not in production.
  (let [db    (embedded-h2/fresh-db!)
        store (s3/make-s3 {:bucket "kal-media-prod"})
        sent  (atom nil)
        req   {:tenant     {:hostname "andrewslai.com" :tenant-name "andrewslai.com" :asset-store "andrewslai.com"}
               :components {:static-content-adapters {tenant/media-store store}
                            :database                db
                            :notify-image-resizer!   (fn [& {:keys [message message-attributes]}]
                                                       (reset! sent {:message message :attrs message-attributes}))}}
        ;; stub put-file so the contract test never hits real S3
        {:keys [photo-id]} (with-redefs [fs/put-file (fn [& _] nil)]
                             (photo/process-photo-upload! req {:filename "x.jpg" :tempfile (tmp "IMG")}))
        raw-key              (format "media/%s/raw.jpg" photo-id)
        {:keys [bucket key]} (fs/write-location store raw-key)]
    (is (= (format "s3://%s/%s" bucket key) (:message @sent)))   ;; body = write-location URL, verbatim
    (is (nil? (:attrs @sent)))))                                 ;; NO attributes — resizer ignores them
