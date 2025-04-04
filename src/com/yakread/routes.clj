(ns com.yakread.routes)

(def subs-page     'com.yakread.app.subscriptions/page-route)
(def unsubscribe!  'com.yakread.app.subscriptions/unsubscribe)
(def add-sub-page  'com.yakread.app.subscriptions.add/page-route)
(def view-sub-page 'com.yakread.app.subscriptions.view/page-route)

(def favorites-page 'com.yakread.app.favorites/page)
(def add-favorite-page 'com.yakread.app.favorites.add/page)

(def bookmarks-page 'com.yakread.app.read-later/page)
(def add-bookmark-page 'com.yakread.app.read-later.add/page)
