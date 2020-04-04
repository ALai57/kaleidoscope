(ns andrewslai.clj.persistence.core)

(defprotocol Persistence
  (save-article! [_])
  (get-article-metadata [_ article-name])
  (get-article-content [_ article-id])
  (get-full-article [_ article-name])
  (get-all-articles [_])
  (get-resume-info [_])
  (create-user! [_ user]))
