.PHONY: all fmt fmt-check lint test identity-check release-check

all: fmt-check lint test identity-check

fmt:
	clojure -M:format/fix

fmt-check:
	clojure -M:format

lint:
	clojure -M:lint/clj-kondo
	clojure -M:lint/splint

test:
	clojure -M:test

identity-check:
	bin/identity-check

release-check:
	bin/verify-generated-repo --mode pre-tag --source-root "$(CURDIR)" --core-release release/msr04-release.json
