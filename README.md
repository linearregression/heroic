# [![Heroic](/logo.42.png?raw=true "The Heroic Time Series Database")](/assets/logo_on_light.svg) Heroic

[![Build Status](https://travis-ci.org/spotify/heroic.svg?branch=master)](https://travis-ci.org/spotify/heroic)
[![Codecov](https://img.shields.io/codecov/c/github/spotify/heroic.svg)](https://codecov.io/gh/spotify/heroic)
[![License](https://img.shields.io/github/license/spotify/heroic.svg)](LICENSE)

A scalable time series database based on Bigtable, Cassandra, and Elasticsearch.
Go to https://spotify.github.io/heroic/ for documentation, please join [`#heroic at Freenode`](irc://freenode.net/heroic) if you need help or want to chat.

This project adheres to the [Open Code of Conduct](https://github.com/spotify/code-of-conduct/blob/master/code-of-conduct.md).
By participating, you are expected to honor this code.

**Stability Disclaimer:**
Heroic is an evolving project, and should in its current state be considered *unstable*.
Do not use in production unless you are willing to spend time with it, experiment and contribute.
Not doing so might result in losing your data to goblins. It is currently not on a release schedule and is not versioned. At Spotify we rely on multiple *release forks* that we actively maintain and flip between.

## Building

Java 8 is required.

There are some repackaged dependencies that you have to make available, you do
this by running `tools/install-repackaged`.

```bash
$ tools/install-repackaged
Installing repackaged/x
...
```

After this, the project is built using Maven:

```bash
$ mvn package
```

This will cause the `heroic-dist` module to produce a shaded jar that contains
all required dependencies.

#### Running Tests

You can run unit tests with MAven:

```
$ mvn test
```

A more comprehensive test suite is enabled with the `environment=test`
property.

```
$ mvn -D environment=test verify
```

This adds:

* [Checkstyle](http://checkstyle.sourceforge.net/)
* [FindBugs](http://findbugs.sourceforge.net/)
* [Integration Tests with Maven Failsafe Plugin](http://maven.apache.org/surefire/maven-failsafe-plugin/)
* [Coverage Reporting with Jacoco](http://eclemma.org/jacoco/)

It is strongly recommended that you run the full test suite before setting up a
pull request, otherwise it will be rejected by Travis.

#### Remote Integration Tests

Integration tests are configured to run remotely depending on a set of system
properties.

| Property                                            | Description                                            |
|-----------------------------------------------------|--------------------------------------------------------|
| -D elasticsearch.version=&lt;version&gt;            | Use the given client version when building the project |
| -D it.elasticsearch.remote=true                     | Run Elasticsearch tests against a remote database      |
| -D it.elasticsearch.seed=&lt;seed&gt;               | Use the given seed (default: `localhost`)              |
| -D it.elasticsearch.clusterName=&lt;clusterName&gt; | Use the given cluster name (default: `elasticsearch`)  |

The following is an example Elasticsearch remote integration test:

```
$> mvn -P integration-tests \
    -D elasticsearch.version=1.7.5 \
    -D it.elasticsearch.remote=true \
    clean verify
```

#### Coverage

[![Coverage](https://codecov.io/gh/spotify/heroic/branch/master/graphs/icicle.svg)](https://codecov.io/gh/spotify/heroic/branch/master)

There's an ongoing project to improve test coverage.
Clicking the above graph will bring you to [codecov.io](https://codecov.io/gh/spotify/heroic/branches/master), where you can find areas to focus on.

#### Speedy Building

For a speedy build without tests and checks, you can run:

```bash
$ mvn -D maven.test.skip=true package
```

#### Building a Debian Package

This project does not provide a single debian package, this is primarily
because the current nature of the service (alpha state) does not mesh well with
stable releases.

Instead, you are encouraged to build your own using the provided scripts in
this project.

First run the `prepare-sources` script:

```bash
$ debian/bin/prepare-sources myrel 1
```

`myrel` will be the name of your release, it will be part of your package name
`debian-myrel`, it will also be suffixed to all helper tools (e.g.
`heroic-myrel`).

For the next step you'll need a Debian environment:

```bash
$ dpkg-buildpackage -uc -us
```

If you encounter problems, you can troubleshoot the build with `DH_VERBOSE`:

```bash
$ env DH_VERBOSE=1 dpkg-buildpackage -uc -us
```

## Hacking

* [A Guide to Dagger 2](docs/guide-to-dagger2.md)
* [Using IDEA](idea)
* [Using Eclipse](eclipse)

#### Module Orientation

The Heroic project is split into a couple of modules.

The most critical one is [`heroic-component`](heroic-component). It contains
interfaces, value objects, and the basic set of dependencies necessary to glue
different components together.

Submodules include [`metric`](metric), [`suggest`](suggest),
[`metadata`](metadata), and [`aggregation`](aggregation). The first three
contain various implementations of the given backend type, while the latter
provides aggregation methods.

[`heroic-core`](heroic-core) contains the
[`com.spotify.heroic.HeroicCore`](heroic-core/src/main/java/com/spotify/heroic/HeroicCore.java)
class which is the central building block for setting up a Heroic instance.

[`heroic-elasticsearch-utils`](heroic-elasticsearch-utils) is a collection of
utilities for interacting with Elasticsearch. This is separate since we have
more than one backend that needs to talk with elasticsearch.

[`heroic-parser`](heroic-parser) provides an Antlr4 implementation of
[`com.spotify.heroic.grammar.QueryParser`](heroic-component/src/main/java/com/spotify/heroic/grammar/QueryParser.java),
which is used to parse the Heroic DSL.

[`heroic-shell`](heroic-shell) contains
[`com.spotify.heroic.HeroicShell`](heroic-shell/src/main/java/com/spotify/heroic/HeroicShell.java),
a shell capable of either running a standalone, or connecting to an existing
Heroic instance for administration.

[`heroic-all`](heroic-all) contains dependencies and references to all modules
that makes up a Heroic distribution. This is also where profiles are defined
since they need to have access to all dependencies.

Anything in the [`repackaged`](repackaged) directory is dependencies that
include one or more Java packages that must be relocated to avoid conflicts.
These are exported under the `com.spotify.heroic.repackaged` groupId.

Finally there is [`heroic-dist`](heroic-dist), a small project that depends on
[`heroic-all`](heroic-all), [`heroic-shell`](heroic-shell), and a logging
implementation. Here is where everything is bound together into a distribution
&mdash; a shaded jar. It also provides the entry-point for services, namely
[`com.spotify.heroic.HeroicService`](heroic-dist/src/main/java/com/spotify/heroic/HeroicService.java).

#### Bypassing Validation

To bypass automatic formatting and checkstyle validation you can use the
following stanza:

```java
// @formatter:off
final List<String> list = ImmutableList.of(
   "Welcome to...",
   "... The Wild West"
);
// @formatter:on
```

To bypass a FindBugs error, you should use the `@SupressFBWarnings` annotation.

```java
@SupressFBWarnings(value="FINDBUGS_ERROR_CODE", justification="I Know Better Than FindBugs")
public class IKnowBetterThanFindbugs() {
    // ...
}
```

## HeroicShell

Heroic comes with a shell that contains many useful tasks, these can either
be run in a readline-based shell with some basic completions and history, or
standalone.

You can use the following helper script to run the shell directly from the
project.

```bash
$ tools/heroic-shell [opts]
```

There are a few interesting options available, most notably is `--connect` that
allows the shell to connect to a remote heroic instance.

See `-h` for a full listing of options.

You can run individual tasks in _standalone_ mode, giving you a bit more
options (like redirecting output) through the following.

```bash
$ tools/heroic-shell <heroic-options> -- com.spotify.heroic.shell.task.<task-name> <task-options>
```

There are also profiles that can be activated with the `-P <profile>` switch,
available profiles are listed in `--help`.

## Repackaged Dependencies

* [repackaged/bigtable](./repackaged/bigtable/)
* [repackaged/datastax](./repackaged/datastax/)

These are third-party dependencies that has to be repackaged to avoid binary
incompatibilities with dependencies.

Every time these are upgraded, they must be inspected for new conflicts.
The easiest way to do this, is to build the project and look at the warnings
for the shaded jar.

```
$> mvn clean package -D maven.test.skip=true
...
[WARNING] foo-3.5.jar, foo-4.5.jar define 10 overlapping classes: 
[WARNING]   - com.foo.ConflictingClass
...
```

This would indicate that there is a package called foo with overlapping
classes.

You can find the culprit using the `dependency` plugin.

```
$> mvn package dependency:tree
```
