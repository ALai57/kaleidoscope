(ns kaleidoscope.init.env-test
  (:require [clojure.test :refer :all]
            [kaleidoscope.api.image-transcriber :as transcriber]
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

(deftest notify-image-resizer-none-launcher-accepts-keyword-args
  (testing "the \"none\" no-op notifier tolerates the keyword-arg call made by new-image"
    (let [none-launcher (get-in sut/kaleidoscope-notify-image-resizer-boot-instructions
                                [:launchers "none"])
          notifier      (none-launcher {})]
      ;; new-image invokes the notifier with these six positional args; a bare
      ;; clojure.core/identity throws ArityException here (Bugsnag 6a567ddc).
      (is (nil? (notifier :subject             "image-resize-requested"
                          :message             "s3://host/media/photo/raw.png"
                          :message-attributes  {"hostname" "host" "extension" "png"}))))))

(deftest image-transcriber-boots-mock-by-default-test
  (testing "the default launcher yields a MockTranscriber under :kaleidoscope-image-transcriber"
    (let [system (sut/start-system! [sut/kaleidoscope-image-transcriber-boot-instructions] {})]
      (is (satisfies? transcriber/ImageTranscriber (:kaleidoscope-image-transcriber system)))))
  (testing "claude-vision launcher builds a Claude transcriber from ANTHROPIC_API_KEY"
    (let [system (sut/start-system! [sut/kaleidoscope-image-transcriber-boot-instructions]
                                    {"KALEIDOSCOPE_IMAGE_TRANSCRIBER_TYPE" "claude-vision"
                                     "ANTHROPIC_API_KEY" "sk-test"})]
      (is (satisfies? transcriber/ImageTranscriber (:kaleidoscope-image-transcriber system))))))
