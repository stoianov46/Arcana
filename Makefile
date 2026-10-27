PREFIX ?= $(HOME)/.local
APP_DIR = $(PREFIX)/lib/arcana
BIN = $(PREFIX)/bin/arcana
DIST = app/build/install/arcana

.PHONY: build install uninstall dist

build:
	./gradlew installDist

# a zip anyone can unpack and run (needs a Java 17+ runtime): app/build/distributions/arcana.zip, with bin/arcana inside
dist:
	./gradlew distZip
	@echo "Built: app/build/distributions/arcana.zip (unzip it, then run arcana/bin/arcana --init <vault> once, or just start it)"

install: build
	rm -rf "$(APP_DIR)"
	mkdir -p "$(APP_DIR)" "$(PREFIX)/bin"
	cp -R $(DIST)/. "$(APP_DIR)/"
	ln -sf "$(APP_DIR)/bin/arcana" "$(BIN)"
	@echo "Installed: $(BIN)"
	@case ":$$PATH:" in *":$(PREFIX)/bin:"*) ;; *) echo "Add $(PREFIX)/bin to your PATH to run \"arcana\"";; esac

uninstall:
	rm -f "$(BIN)"
	rm -rf "$(APP_DIR)"
	@echo "Removed arcana from $(PREFIX)"
