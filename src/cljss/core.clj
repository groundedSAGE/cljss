(ns cljss.core
  (:require [cljss.utils :as utils :refer [build-css escape-val resolve-get cljs-env?]]
            [cljss.font-face :as ff]
            [cljss.inject-global :as ig]
            [cljss.builder :refer [status? build-styles]]
            [cljss.collect :refer [dynamic?]]
            [clojure.string :as cstr]
            [cljss.specs]
            [cljss.ssr :as ssr]))

(def *exclude-static?* false)

(defn- ->status-styles [styles]
  (let [status (filterv status? styles)
        sprops (keys status)]
    (->> status
         (map (fn [[prop styles]]
                (->> styles
                     (map (fn [[rule value]] [rule prop value])))))
         (mapcat identity)
         (group-by first)
         (map (fn [[rule states]]
                (let [svals (map last states)
                      args  (mapv (fn [_] (gensym "var")) svals)]
                  [rule
                   `(with-meta
                      (fn ~args
                        (cond ~@(->> svals
                                     (map-indexed (fn [idx value]
                                                    [(nth args idx) value]))
                                     (mapcat identity)
                                     ((fn [coll] (concat coll [:else (get styles rule)]))))))
                      (list ~@(mapv second states)))])))
         (into {})
         (merge styles)
         (#(apply dissoc % sprops)))))

(defn normalize-class [cls sym]
  (-> cls
      (clojure.core/str "/" sym)
      (clojure.string/replace "." "_")
      (clojure.string/replace "/" "__")))

(defmacro sym->cls-name [sym]
  `(-> ~'&env :ns :name (clojure.core/str "/" ~sym) (clojure.string/replace "." "_") (clojure.string/replace "/" "__")))

(defn var->cls-name [var]
  (-> (resolve var) meta :ns str (normalize-class var)))

(defmacro sym->cmp-name [sym]
  `(-> ~'&env :ns :name (clojure.core/str "." ~sym)))

(defn var->cmp-name [var]
  (-> (resolve var) meta :ns :name (clojure.core/str "." var)))

(defmacro defstyles
  "Takes var name, a vector of arguments and a hash map of styles definition.
   Generates class name, static and dynamic parts of styles.
   Returns a function that calls `cljss.core/css` to inject styles at runtime
   and returns generated class name."
  [sym args styles]
  (let [cls-name# (if (cljs-env? &env)
                    (sym->cls-name sym)
                    (var->cls-name sym))]
    (let [[_ static# vals#] (build-styles cls-name# styles)]
      (if-not (cljs-env? &env)
        `(defn ~sym ~args
           (cljss.ssr/add-css ~cls-name# ~static# ~vals#))
        (if *exclude-static?*
          (do
            (swap! cljss.ssr/*ssr-ctx* assoc-in [:static cls-name#] static#)
            `(defn ~sym ~args
               (cljss.core/css ~cls-name# "" ~vals#)))
          `(defn ~sym ~args
             (cljss.core/css ~cls-name# ~static# ~vals#)))))))

(defn- vals->array [vals]
  (let [arrseq (mapv (fn [[var val]] `(cljs.core/array ~var ~val)) vals)]
    `(cljs.core/array ~@arrseq)))

(defn ->styled
  "Takes var name, HTML tag name and a hash map of styles definition.
   Returns a var bound to the result of calling `cljss.core/styled`,
   which produces React element and injects styles."
  [tag styles cls env]
  (let [tag    (name tag)
        styles (->status-styles styles)
        [_ static values] (build-styles cls styles)
        values (if (cljs-env? env)
                 (vals->array values)
                 values)
        attrs  (->> styles vals (filterv keyword?))]
    (if (cljs-env? env)
      [tag static values `(cljs.core/array ~@attrs)]
      [tag static values attrs])))

(defn -styled [tag cls static vars attrs]
  (let [cls (str cls "-" (gensym))]
    (fn [props & children]
      (let [[props children] (if (map? props)
                               [props children]
                               [{} (into [props] children)])
            [var-class static vars] (utils/-mk-var-class props vars cls static)
            meta-attrs (utils/-meta-attrs vars)
            className  (str (utils/-compile-class-name props) var-class)
            props      (-> (apply dissoc props (concat attrs meta-attrs [:class :class-name :className]))
                           (assoc :className className)
                           utils/-camel-case-attrs)]
        (ssr/add-css var-class static vars)
        [tag props children]))))

(defmacro make-styled []
  '(def styled cljss.core/-styled))

(defn- keyframes-styles [idx styles]
  (let [dynamic (filterv dynamic? styles)
        static  (filterv (comp not dynamic?) styles)
        [vars idx]
        (reduce
          (fn [[vars idx] [rule]]
            [(conj vars [rule idx])
             (inc idx)])
          [[] idx]
          dynamic)
        vals    (mapv (fn [[_ var] [_ exp]] [(str "var(" var ")") exp]) vars dynamic)
        static  (->> vars
                     (map (fn [[rule var]] [rule (str "var(" var ")")]))
                     (concat static)
                     (map (fn [[rule val]] (str (name rule) ":" (escape-val rule val) ";")))
                     (cstr/join "")
                     (#(str "{" % "}")))]
    [static vals idx]))

(defn- ->ks-key [k]
  (cond
    (keyword? k) (name k)
    (number? k) (str k "%")
    (vector? k) (->> k (map ->ks-key) (cstr/join ","))
    :else k))

(defn- build-keyframes [keyframes]
  (let [[ks [statics vals]]
        (->> keyframes
             (reduce
               (fn [[ks [static vals idx]] [k styles]]
                 (let [[s v idx] (keyframes-styles idx styles)]
                   [(conj ks (->ks-key k))
                    [(conj static s) (into vals v) idx]]))
               [[] [[] [] 1]]))]
    [(->> (interleave ks statics)
          (apply str))
     vals]))

(defn -css-keyframes [cls static vars]
  (let [inner
                  (reduce
                    (fn [s [id val]] (cstr/replace s id val))
                    static
                    vars)
        anim-name (str "animation-" cls "-" (hash vars))
        keyframes (str "@keyframes " anim-name "{" inner "}")]
    (swap! ssr/*ssr-ctx* assoc-in [:styles anim-name] keyframes)
    anim-name))

(defmacro defkeyframes
  "Takes var name, a vector of arguments and a hash map of CSS keyframes definition.
  Returns a function that calls `cljss.core/css-keyframes` to inject styles at runtime\n
  and returns generated CSS animation name that can be used in CSS `animation` rule.

  (defkeyframes spin [start end]\n    {:from {:transform (str \"rotate(\" start \"deg)\")}\n     :to   {:transform (str \"rotate(\" end \"deg)\")}})

  (defstyled Spinner :div\n    {:animation (str (spin 0 180) \" 1s ease infinite\")})"
  [var args keyframes]
  (let [cls# (sym->cls-name var)
        [keyframes# vals#] (build-keyframes keyframes)]
    (if-not (cljs-env? &env)
      `(defn ~var ~args
         (-css-keyframes ~cls# ~keyframes# ~vals#))
      `(defn ~var ~args
         (cljss.core/css-keyframes ~cls# ~keyframes# ~vals#)))))

(defmacro font-face
  "Takes a hash of font descriptors and produces CSS string of @font-face declaration.
  Returns a function that injects styles at runtime."
  [descriptors]
  (let [css# (ff/font-face (resolve-get descriptors))
        cls# (hash css#)]
    `(cljss.core/css ~cls# ~css# [])))

(defmacro inject-global
  "Takes a hash of global styles definitions and produces CSS string.
  Returns a sequence of calls to inject styles at runtime."
  [css]
  (let [css (ig/inject-global (resolve-get css))]
    `(do ~@(->> css (map (fn [[cls# css#]] `(cljss.core/css ~cls# ~css# [])))))))
