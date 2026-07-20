(ns kaleidoscope.init.env-test
  (:require [clojure.test :refer :all]
            [kaleidoscope.api.image-transcriber :as transcriber]
            [kaleidoscope.api.resize :as resize]
            [kaleidoscope.http-api.tenant :as tenant]
            [kaleidoscope.init.env :as sut]
            [kaleidoscope.main :as main]
            [malli.instrument :as mi]))

(deftest env->pg-conn-throws-when-missing-env-vars
  (main/initialize-schema-enforcement!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing DB name. Set via KALEIDOSCOPE_DB_NAME environment variable."
                        (sut/env->pg-conn {})))
  (is (not (re-matches #"Missing DB name. Set via KALEIDOSCOPE_DB_NAME environment variable."
                       (try
                         (sut/env->pg-conn {"KALEIDOSCOPE_DB_NAME" "Hi"})
                         (throw (Exception. "Should blow up before this with ex-info"))
                         (catch clojure.lang.ExceptionInfo e
                           (ex-message e))))))

  (mi/unstrument!))

(deftest environment-name-test
  (testing "defaults to production when KALEIDOSCOPE_ENV is unset"
    (is (= "production" (sut/environment-name {}))))

  (testing "reads KALEIDOSCOPE_ENV when set"
    (is (= "ephemeral-foo" (sut/environment-name {"KALEIDOSCOPE_ENV" "ephemeral-foo"})))))

(deftest env->bugsnag-test
  (testing "release-stage follows KALEIDOSCOPE_ENV, defaulting to production"
    (is (= "production" (:release-stage (sut/env->bugsnag {"KALEIDOSCOPE_BUGSNAG_KEY" "key"}))))
    (is (= "ephemeral-foo" (:release-stage (sut/env->bugsnag {"KALEIDOSCOPE_BUGSNAG_KEY" "key"
                                                              "KALEIDOSCOPE_ENV"        "ephemeral-foo"}))))))

(deftest s3-static-content-launcher-registers-isolated-ephemeral-asset-store-when-configured
  (let [s3-launcher (get-in sut/kaleidoscope-static-content-adapter-boot-instructions [:launchers "s3"])]
    (testing "setting the asset bucket and prefix registers the isolated store under its fixed name"
      (let [adapters (s3-launcher {"KALEIDOSCOPE_TENANT_ASSET_BUCKET" "kal-ephemeral"
                                   "KALEIDOSCOPE_TENANT_ASSET_PREFIX" "tenant-assets/xyz/"})
            store    (get adapters "ephemeral-tenant-assets")]
        (is (= "kal-ephemeral" (:bucket store)))
        (is (= "tenant-assets/xyz/" (:prefix store)))))

    (testing "unset asset bucket env var leaves the isolated store entry absent"
      (is (nil? (get (s3-launcher {}) "ephemeral-tenant-assets"))))

    (testing "the isolated store entry is additive, not an override of any tenant entry"
      (is (= "andrewslai.com" (:bucket (get (s3-launcher {"KALEIDOSCOPE_TENANT_ASSET_BUCKET" "kal-ephemeral"}) "andrewslai.com")))))))

(deftest s3-static-content-launcher-overrides-client-shell-bucket-when-configured
  (let [s3-launcher (get-in sut/kaleidoscope-static-content-adapter-boot-instructions [:launchers "s3"])]
    (testing "unset client env vars serve the shell from the default kaleidoscope.client bucket"
      (let [client (get (s3-launcher {}) "kaleidoscope.client")]
        (is (= "kaleidoscope.client" (:storage-root client)))
        (is (nil? (:prefix client)))))

    (testing "KALEIDOSCOPE_CLIENT_BUCKET redirects the shell to the given bucket"
      (let [client (get (s3-launcher {"KALEIDOSCOPE_CLIENT_BUCKET" "kal-ephemeral"}) "kaleidoscope.client")]
        (is (= "kal-ephemeral" (:storage-root client)))
        (is (nil? (:prefix client)))))

    (testing "KALEIDOSCOPE_CLIENT_PREFIX scopes the shell adapter to that key prefix"
      (let [client (get (s3-launcher {"KALEIDOSCOPE_CLIENT_BUCKET" "kal-ephemeral"
                                      "KALEIDOSCOPE_CLIENT_PREFIX" "eph-foo/"}) "kaleidoscope.client")]
        (is (= "kal-ephemeral" (:storage-root client)))
        (is (= "eph-foo/" (:prefix client)))))))

(deftest registry-test
  (let [s3-launcher (get-in sut/kaleidoscope-static-content-adapter-boot-instructions [:launchers "s3"])]
    (is (contains? (set (map :hostname (sut/read-tenants))) "andrewslai.com"))
    (is (= "wedding"          (:bucket (get (s3-launcher {}) "caheriaguilar.and.andrewslai.com")))) ; bucket≠host proves file read
    (is (= "kaleidoscope.pub" (:bucket (get (s3-launcher {}) "kaleidoscope.pub"))))))

(deftest env->kaleidoscope-image-resizer-type-test
  (testing "defaults to :in-process when KALEIDOSCOPE_IMAGE_RESIZER_TYPE is unset"
    (is (= :in-process (sut/env->kaleidoscope-image-resizer-type {}))))

  (testing "resolves \"in-process\" and \"none\""
    (is (= :in-process (sut/env->kaleidoscope-image-resizer-type {"KALEIDOSCOPE_IMAGE_RESIZER_TYPE" "in-process"})))
    (is (= :none (sut/env->kaleidoscope-image-resizer-type {"KALEIDOSCOPE_IMAGE_RESIZER_TYPE" "none"}))))

  (testing "throws on an unrecognized value"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"KALEIDOSCOPE_IMAGE_RESIZER_TYPE had invalid value \[bogus\]"
                          (sut/env->kaleidoscope-image-resizer-type {"KALEIDOSCOPE_IMAGE_RESIZER_TYPE" "bogus"})))))

(def ^:private base-boot-env
  {"KALEIDOSCOPE_DB_TYPE"            "embedded-h2"
   "KALEIDOSCOPE_AUTH_TYPE"          "always-unauthenticated"
   "KALEIDOSCOPE_AUTHORIZATION_TYPE" "public-access"})

(deftest prepare-kaleidoscope-resize-gate-test
  (testing "in-process with a media store present builds a real gate over that store"
    (let [system (sut/start-system! sut/DEFAULT-BOOT-INSTRUCTIONS
                                    (merge base-boot-env
                                           {"KALEIDOSCOPE_STATIC_CONTENT_TYPE" "s3"
                                            "KALEIDOSCOPE_MEDIA_BUCKET"        "kal-media-test"
                                            "KALEIDOSCOPE_IMAGE_RESIZER_TYPE"  "in-process"}))
          {:keys [resize-gate static-content-adapters]} (sut/prepare-kaleidoscope system)
          media-store (get static-content-adapters tenant/media-store)]
      (try
        (is (some? media-store))
        (is (= media-store (:store resize-gate)))
        (is (some? (:queue resize-gate)))
        (finally (resize/stop! resize-gate)))))

  (testing "\"none\" yields a no-op gate even when a media store is present"
    (let [system (sut/start-system! sut/DEFAULT-BOOT-INSTRUCTIONS
                                    (merge base-boot-env
                                           {"KALEIDOSCOPE_STATIC_CONTENT_TYPE" "s3"
                                            "KALEIDOSCOPE_MEDIA_BUCKET"        "kal-media-test"
                                            "KALEIDOSCOPE_IMAGE_RESIZER_TYPE"  "none"}))
          {:keys [resize-gate]} (sut/prepare-kaleidoscope system)]
      (is (nil? (:store resize-gate)))
      (is (false? (resize/enqueue-warm! resize-gate (random-uuid) "png")))
      (is (= :no-raw (resize/heal-or-enqueue! resize-gate (random-uuid) "thumbnail" "png")))
      (resize/stop! resize-gate)))

  (testing "no media store present (default local dev config) yields a no-op gate, regardless of the KALEIDOSCOPE_IMAGE_RESIZER_TYPE default"
    (let [system (sut/start-system! sut/DEFAULT-BOOT-INSTRUCTIONS
                                    (merge base-boot-env
                                           {"KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}))
          {:keys [resize-gate]} (sut/prepare-kaleidoscope system)]
      (is (nil? (:store resize-gate)))
      (is (false? (resize/enqueue-warm! resize-gate (random-uuid) "png")))
      (is (= :no-raw (resize/heal-or-enqueue! resize-gate (random-uuid) "thumbnail" "png")))
      (resize/stop! resize-gate))))

(deftest media-store-is-plain-store-when-no-fallback
  (let [adapters ((get-in sut/kaleidoscope-static-content-adapter-boot-instructions
                          [:launchers "s3"])
                  {"KALEIDOSCOPE_MEDIA_BUCKET" "kal-media"})]
    (is (= "kal-media" (:bucket (get adapters tenant/media-store))))))

(deftest media-store-is-read-through-overlay-when-fallback-set
  (let [adapters ((get-in sut/kaleidoscope-static-content-adapter-boot-instructions
                          [:launchers "s3"])
                  {"KALEIDOSCOPE_MEDIA_BUCKET"          "kal-media"
                   "KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET" "kal-media-prod"})
        media    (get adapters tenant/media-store)]
    (is (= "kal-media" (:bucket (:writer media))))                            ;; writes -> own bucket only
    (is (= ["kal-media" "kal-media-prod"] (map :bucket (:readers media))))))  ;; reads -> own then prod

(deftest media-store-absent-when-media-bucket-unset
  ;; The Phase-1 linchpin: prod deploys this build with MEDIA_BUCKET unset, so
  ;; the media store must simply not be registered (uploads/serves fall back to
  ;; the per-tenant adapter, byte-identical to before).
  (let [adapters ((get-in sut/kaleidoscope-static-content-adapter-boot-instructions
                          [:launchers "s3"])
                  {})]
    (is (nil? (get adapters tenant/media-store)))))

(deftest image-transcriber-boots-mock-by-default-test
  (testing "the default launcher yields a MockTranscriber under :kaleidoscope-image-transcriber"
    (let [system (sut/start-system! [sut/kaleidoscope-image-transcriber-boot-instructions] {})]
      (is (satisfies? transcriber/ImageTranscriber (:kaleidoscope-image-transcriber system)))))
  (testing "claude-vision launcher builds a Claude transcriber from ANTHROPIC_API_KEY"
    (let [system (sut/start-system! [sut/kaleidoscope-image-transcriber-boot-instructions]
                                    {"KALEIDOSCOPE_IMAGE_TRANSCRIBER_TYPE" "claude-vision"
                                     "ANTHROPIC_API_KEY" "sk-test"})]
      (is (satisfies? transcriber/ImageTranscriber (:kaleidoscope-image-transcriber system))))))
