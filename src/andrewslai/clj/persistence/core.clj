(ns andrewslai.clj.persistence.core)

(defprotocol Persistence
  (save-article! [_])
  (get-article [_])
  (get-all-articles [_])
  (get-article-metadata [_]))
