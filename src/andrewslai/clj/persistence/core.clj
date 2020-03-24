(ns andrewslai.clj.persistence.core)

(defprotocol Persistence
  (save-article! [_])
  (get-full-article [_ article-name])
  (get-all-articles [_]))
