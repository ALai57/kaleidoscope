(ns andrewslai.clj.persistence.core)

(defprotocol ArticlePersistence
  (save-article! [_])
  (get-article-metadata [_ article-name])
  (get-article-content [_ article-id])
  (get-full-article [_ article-name])
  (get-all-articles [_])
  (get-resume-info [_]))
