(ns com.yakread.model.admin 
  (:require
   [com.biffweb :as biff :refer [q]]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]])
  (:import
   [java.time ZoneId]))

(defresolver recent-users [{:biff/keys [db now]} _]
  {::pco/output [{:admin/recent-users [:xt/id]}]}
  {:admin/recent-users
   (q db
      '{:find [user]
        :keys [xt/id]
        :in [t0]
        :where [[user :user/joined-at t]
                [(<= t0 t)]]}
      (.minusSeconds now (* 60 60 24 7)))})

(defresolver dau [{:biff/keys [db now]} _]
  {:admin/dau
   (->> (q db
           '{:find [user viewed-at]
             :in [t0]
             :where [[usit :user-item/viewed-at viewed-at]
                     [usit :user-item/user user]
                     [(<= t0 viewed-at)]]}
           (.minusSeconds now (* 60 60 24 30)))
        (mapv (fn [[_ viewed-at]]
                (.. viewed-at
                    (atZone (ZoneId/of "America/Denver"))
                    (toLocalDate))))
        frequencies)})

(defresolver revenue [{:biff/keys [db now]} _]
  {:admin/revenue
   (->> (q db
           '{:find [cost created-at]
             :in [t0]
             :where [[ad-click :ad.click/created-at created-at]
                     [ad-click :ad.click/cost cost]
                     [(<= t0 created-at)]]}
           (.minusSeconds now (* 60 60 24 30)))
        (reduce (fn [acc [cost t]]
                  (let [date (.. t
                                 (atZone (ZoneId/of "America/Denver"))
                                 (toLocalDate))]
                    (update acc date (fnil + 0) cost)))
                {}))})

(defresolver ads [{:biff/keys [db now]} _]
  {::pco/output [{:admin/ads [:xt/id]}]}
  {:admin/ads (vec (q db
                      '{:find [ad]
                        :keys [xt/id]
                        :where [[ad :ad/user]]}))})

(def module
  {:resolvers [recent-users
               dau
               revenue
               ads]})
