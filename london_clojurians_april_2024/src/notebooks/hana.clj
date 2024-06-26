(ns notebooks.hana
  (:require [scicloj.kindly.v4.kind :as kind]
            [scicloj.noj.v1.paths :as paths]
            [scicloj.tempfiles.api :as tempfiles]
            [tablecloth.api :as tc]
            [tablecloth.column.api :as tcc]
            [tech.v3.dataset :as ds]
            [aerial.hanami.common :as hc]
            [aerial.hanami.templates :as ht]
            [scicloj.metamorph.ml.toydata :as toydata]
            [tech.v3.datatype.functional :as fun]
            [tech.v3.dataset.modelling :as modelling]
            [scicloj.metamorph.ml :as ml]))


(defn build [[f arg]]
  (f arg))

(defn prepare-data [dataset]
  (when dataset
    (let [{:keys [path _]}
          (tempfiles/tempfile! ".csv")]
      (-> dataset
          (ds/write! path))
      {:values (slurp path)
       :format {:type "csv"}})))

(defn safe-update [m k f]
  (if (m k)
    (update m k f)
    m))


(defn xform [context]
  (let [{:keys [hana/stat]} (:args context)
        context1 (if stat
                   (stat context)
                   context)
        {:keys [template args metamorph/data]} context1]
    (-> template
        (hc/xform args)
        (cond-> data
          (assoc :data (prepare-data data)))
        kind/vega-lite)))

(defn layered-xform [{:as context
                      :keys [template args metamorph/data]}]
  (-> context
      (update :template
              safe-update
              :layer
              (partial
               mapv
               (fn [layer]
                 (-> layer
                     ;; merge the toplevel args
                     ;; with the layer's
                     ;; specific args
                     (update :args (partial merge args))
                     (update :metamorph/data #(or % data))
                     xform))))
      xform))

(defn svg-rendered [vega-lite-template]
  (assoc vega-lite-template
         :usermeta
         {:embedOptions {:renderer :svg}}))

(def view-base (svg-rendered ht/view-base))
(def point-chart (svg-rendered ht/point-chart))
(def line-chart (svg-rendered ht/line-chart))

(def point-layer ht/point-layer)
(def line-layer ht/line-layer)

(defn plot
  ([dataset args]
   (plot dataset
         view-base
         args))
  ([dataset template args]
   (kind/fn [layered-xform
             {:metamorph/data dataset
              :template template
              :args args}])))

(defn layer
  ([context template args]
   (if (tc/dataset? context)
     (layer (plot context {})
            template
            args)
     (-> context
         (update
          1
          update
          :template
          update
          :layer
          (comp vec conj)
          {:template template
           :args args})))))


(defn layer-point
  ([context]
   (layer-point context {}))
  ([context args]
   (layer context point-layer args)))

(defn layer-line
  ([context]
   (layer-line context {}))
  ([context args]
   (layer context line-layer args)))

#_(defn var-from-args [kw args]
    (let [[xformed] (hc/xform [kw] args)]
      (when (not= xformed kw)
        xformed)))

(def smooth-stat
  (fn [{:as context
        :keys [template args]}]
    (let [[Y X X-predictors COLOR] (map args [:Y :X :X-predictors :COLOR])
          predictors (or X-predictors [X])
          predictions-fn (fn [dataset]
                           (if (-> predictors count (= 1))
                             ;; simple linear regression
                             (let [predictor-column (-> predictors first dataset)
                                   model (fun/linear-regressor predictor-column
                                                               (dataset Y))]
                               (map model predictor-column))
                             ;; multiple linear regression
                             (let [_ (require 'scicloj.ml.smile.regression)
                                   model (-> dataset
                                             (modelling/set-inference-target Y)
                                             (tc/select-columns (cons Y predictors))
                                             (ml/train {:model-type
                                                        :smile.regression/ordinary-least-square}))]
                               (-> dataset
                                   (tc/drop-columns [Y])
                                   (ml/predict model)
                                   (get Y)))))
          grouping-columns (->> [(-> :COLOR args keyword)]
                                (remove nil?)
                                seq)
          update-data-fn (fn [dataset]
                           (if grouping-columns
                             (-> dataset
                                 (tc/group-by grouping-columns)
                                 (tc/add-or-replace-column Y predictions-fn)
                                 tc/ungroup)
                             (-> dataset
                                 (tc/add-or-replace-column Y predictions-fn))))]
      (-> context
          (update :metamorph/data update-data-fn)))))



(defn layer-smooth
  ([context]
   (layer-smooth context {}))
  ([context args]
   (layer context
          line-layer
          (merge {:hana/stat smooth-stat}
                 args))))

(comment
  (delay
    (-> (toydata/iris-ds)
        (plot point-chart
              {:X :sepal_width
               :Y :sepal_length})))

  (delay
    (-> (toydata/iris-ds)
        (plot {:X :sepal_width
               :Y :sepal_length})
        layer-point
        layer-smooth))

  (delay
    (-> (toydata/iris-ds)
        (plot {:X :sepal_width
               :Y :sepal_length
               :COLOR "species"})
        layer-point
        layer-smooth))

  (delay
    (-> (toydata/iris-ds)
        (plot {:X :sepal_width
               :Y :sepal_length})
        layer-point))

  (delay
    (-> (toydata/iris-ds)
        (plot {:X :sepal_width
               :Y :sepal_length})
        layer-point
        (layer-smooth {:X-predictors [:petal_width
                                      :petal_length]})))

  (delay
    (-> (toydata/iris-ds)
        (plot {:TITLE "dummy"
               :MCOLOR "green"})
        (layer-point
         {:X :sepal_width
          :Y :sepal_length
          :MSIZE 100})
        (layer-line
         {:X :sepal_width
          :Y :sepal_length
          :MSIZE 4
          :MCOLOR "brown"})))
  )
