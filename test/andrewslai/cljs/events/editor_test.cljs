(ns andrewslai.cljs.events.editor-test
  (:require [cljs.test :as t :refer-macros [deftest is testing]]
            [andrewslai.cljs.events.editor :as e]))

(defn paragraph-with [nodes]
  (->> {:document {:nodes [{:object "block"
                            :type "paragraph"
                            :nodes nodes}]}}
       clj->js
       (.fromJSON js/Slate.Value)))

(def plaintext
  (paragraph-with [{:object "text"
                    :leaves [{:text "Hello world!"}]}]))

(def bold
  (paragraph-with [{:object "text"
                    :leaves [{:object "leaf"
                              :marks [{:type "bold"}]
                              :text "Bold text"}]}]))

(def italic
  (paragraph-with [{:object "text"
                    :leaves [{:object "leaf"
                              :marks [{:type "italic"}]
                              :text "Italic text"}]}]))

(deftest paragraph-serialization
  (testing "Serialize plain text"
    (is (= {:html "<p>Hello world!</p>"}
           (e/editor-model->clj plaintext))))
  (testing "Serialize bold text"
    (is (= {:html "<p><strong>Bold text</strong></p>"}
           (e/editor-model->clj bold))))
  (testing "Serialize italic text"
    (is (= {:html "<p><em>Italic text</em></p>"}
           (e/editor-model->clj italic)))))


(deftest editor-text-changes
  (testing "Editor text changed"
    (is (= {:editor-metadata {:metadata "Something"}}
           (e/editor-metadata-changed {} [nil {:metadata "Something"}])))))
