# Yakread

Source code for [Yakread](https://yakread.com), a reading app that does:

- RSS and newsletter subscriptions
- bookmark URLs to read them later
- algorithmic recommendation, both for helping you manage all the stuff you subscribe to/bookmark
  and for showing you articles that other Yakread users like.

I made Yakread during the final stretch of my "entrepreneurship journey;" now I have a regular job
and Yakread is a side project. I've open sourced it primarily so it can serve as an example of a
nontrivial project built with [Biff](https://biffweb.com/), a Clojure web framework I built and
maintain. You're welcome to self-host it if you want, but I haven't written any documentation for
doing so (other than [Biff's standard deployment
instructions](https://biffweb.com/docs/reference/production/)).

Yakread is also a testing ground for new Biff features; expect to see a bunch of rough unpolished
stuff that may or may not get moved into the framework later.

## Running the app locally

1. Run `clj -M:run generate-config`
2. Create 4 S3 buckets (I use DigitalOcean Spaces) and set the `S3_*` env vars in config.env
3. Run `clj -M:run dev`

Then go to `localhost:8080`.

## Code structure

First, read [Project Layout](https://biffweb.com/docs/reference/project-layout/) and
[Architecture](https://biffweb.com/docs/reference/architecture/) from the Biff docs.

Beyond that, Yakread makes heavy use of [Pathom](https://pathom3.wsscode.com/). Pathom helps us to
split up the codebase into small understandable chunks, with each chunk declaring explicitly the
shape of the data it needs. If you've ever had trouble tracing up through several layers of code to
figure out where a particular piece of data is coming from, Pathom solves that problem.

Yakread also takes [an experimental approach](https://biffweb.com/p/removing-effects/) to dealing
with side effects: effectful code is structured as a state machine, with some states doing pure
computation and other states doing very simple side effects (e.g. "take some input data, perform an
HTTP request, return the output"). This makes unit tests easy to write and removes the need for
mocking.

Speaking of unit tests: Yakread does [inline snapshot testing with EDN
files](https://biffweb.com/p/edn-tests/).

As for the namespace layout:

- `com.yakread.app.*` contains mostly HTTP handlers; this is where all the pages of the web app are
  defined.

- `com.yakread.model.*` contains mostly Pathom resolvers. e.g. database queries and derived data are
  defined here.

- `com.yakread.ui-components.*` also contains Pathom resolvers, but these resolvers return Hiccup
  data structures instead of "regular" data (things from your domain model). Here we define reusable
  UI components that can query Pathom for whatever data they need. For example, there is a UI
  component for rendering an article excerpt as a card, which can then be clicked to view the whole
  article. Code in `com.yakread.app.*` doesn't have to know all the fields or derived data required
  by the card component; it just tells Pathom "give me the card view for this item."

- `com.yakread.work.*` contains queues and scheduled tasks. e.g. there is a module in here that
  handles emailing a daily reading digest to users. This is code that would be deployed on a
  separate worker if I were deploying Yakread on more than a single machine.

- The above four sections all contain Biff modules (see the architecture document linked above);
  none of the namespaces there are required anywhere except from `com.yakread.modules`, an
  auto-generated file that aggregates all the modules.

- `com.yakread.lib.*` contains shared code that _is_ meant to be required from other application
  code.

There is plenty of messiness: Yakread is a real app with real users, and I am trying to get things
done instead of making the code perfect. I've also done plenty of experimenting with different ways
of doing things, and not all of my abandoned approaches have been removed from the codebase yet.

Some notable namespaces (good places to start reading if you're interesting in learning the codebase):

- `com.yakread`, the entry point.
- `com.yakread.app.for-you`, the main page after you sign in.
- `com.yakread.app.subscriptions.add`, a relatively simple CRUD example.
- `com.yakread.model.item`, an example of some Pathom resolvers.
- `com.yakread.model.recommend`, the recommendation algorithm that runs whenever you load the For
  You page.
- `com.yakread.lib.spark`, the collaborative filtering model used by `model.recommend` to recommend
  articles from other users.
- `com.yakread.app.advertise`, `com.yakread.app.admin.advertise`, `com.yakread.app.settings`: some
  Stripe examples.
- `com.yakread.lib.pipeline`, the effect-handling state machine stuff mentioned above. See `make` in
  particular. I'm probably going to rename this namespace.
- `com.yakread.lib.route`, some helper functions/macros Biff uses when defining and referencing HTTP
  handlers. See `defget`, `defpost`, and `href`.
- `com.yakread.lib.ui`, UI components that aren't coupled to your domain model (like `button`, etc)
  and thus don't need to query Pathom themselves.
- `com.yakread.work.digest`, code for sending the daily digest emails.
- `com.yakread.smtp`, code for receiving emails (users can create a `@yakread.com` email address and
  sign up for newsletters with it).

## License

Copyright © 2025 Jacob O'Bryant

Distributed under the source-available [PolyForm](https://polyformproject.org/licenses/) Perimeter
license, which "permits uses other than those that compete with the software."
