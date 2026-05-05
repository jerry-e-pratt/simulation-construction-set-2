# scs2-examples Dependency Tree

This document analyzes the dependency tree for `scs2-examples`, starting from
`scs2-examples/build.gradle.kts` `mainDependencies`. Only `us.ihmc:*` dependencies
are traversed recursively. All other dependencies are shown as leaves.

A flat indented form is provided in [`DEPENDENCY.txt`](DEPENDENCY.txt).

## Legend

- **Local** module: build file lives in this workspace (`*/build.gradle.kts`).
- **GitHub** module: build file fetched from the corresponding tag on
  `github.com/ihmcrobotics/<repo>`.
- **External**: not under `us.ihmc:*`; not traversed.
- **us.ihmc (no GitHub)**: published under `us.ihmc:*` but no source repository
  is available on `ihmcrobotics`; not traversed.
- For multi-source-set repos (`euclid`, `mecano`, `ihmc-yovariables`,
  `ihmc-messager`, `ihmc-ros2-library`, `JFoenix-Group`), the build file used is
  the one for the matching source set (e.g. `euclid-shape:0.22.5` →
  `euclid/shape/build.gradle.kts`). For `scs2-bullet-simulation-debug:source`,
  the `debug` source set of `scs2-bullet-simulation` is used.

## 1. Top-level: `scs2-examples` direct dependencies

```mermaid
flowchart LR
   classDef local fill:#cfe2f3,stroke:#4a90c2,color:#0b3d63
   classDef github fill:#d9ead3,stroke:#6aa84f,color:#274e13
   classDef external fill:#f4cccc,stroke:#cc4125,color:#5b1a11
   classDef noRepo fill:#fff2cc,stroke:#bf9000,color:#5b4500

   ex[scs2-examples]:::local
   ex --> fxyz[org.fxyz3d:fxyz3d:0.6.0]:::external
   ex --> scs[scs2-simulation-construction-set:source]:::local
   ex --> visjfx[scs2-session-visualizer-jfx:source]:::local
   ex --> bullet[scs2-bullet-simulation:source]:::local
   ex --> bulletDbg[scs2-bullet-simulation-debug:source]:::local
```

## 2. Local SCS2 modules — internal graph

Edges show `mainDependencies` between the in-workspace SCS2 modules.

```mermaid
flowchart LR
   classDef local fill:#cfe2f3,stroke:#4a90c2,color:#0b3d63

   scs[scs2-simulation-construction-set]:::local
   sim[scs2-simulation]:::local
   sess[scs2-session]:::local
   slog[scs2-session-logger]:::local
   svjfx[scs2-session-visualizer-jfx]:::local
   sv[scs2-session-visualizer]:::local
   def[scs2-definition]:::local
   shm[scs2-shared-memory]:::local
   sym[scs2-symbolic]:::local
   blt[scs2-bullet-simulation]:::local
   bltDbg["scs2-bullet-simulation<br/>(debug source set)"]:::local

   scs --> sim
   scs --> sess
   scs --> slog
   scs --> svjfx

   sim --> def
   sim --> shm
   sim --> sess

   sess --> def
   sess --> shm
   sess --> sym

   slog --> sess
   slog --> sim

   svjfx --> sim
   svjfx --> sess
   svjfx --> slog
   svjfx --> sv

   sv --> def

   sym --> def
   sym --> shm

   shm --> def

   blt --> sim
   blt --> def
   blt --> shm
   blt --> sess

   bltDbg --> blt
   bltDbg --> svjfx
```

## 3. External `us.ihmc:*` libraries pulled in (from `ihmcrobotics` on GitHub)

This graph shows the published `us.ihmc:*` artifacts referenced (directly or
transitively) by the local SCS2 modules above, traversed through their GitHub
`build.gradle.kts` files. Non-`us.ihmc` transitive dependencies are summarized as
single nodes per library.

```mermaid
flowchart LR
   classDef github fill:#d9ead3,stroke:#6aa84f,color:#274e13
   classDef external fill:#f4cccc,stroke:#cc4125,color:#5b1a11
   classDef noRepo fill:#fff2cc,stroke:#bf9000,color:#5b4500

   eu[euclid:0.22.5]:::github
   eug[euclid-geometry:0.22.5]:::github
   euf[euclid-frame:0.22.5]:::github
   eus[euclid-shape:0.22.5]:::github
   eufs[euclid-frame-shape:0.22.5]:::github
   ic[ihmc-commons:0.35.1]:::github
   iy[ihmc-yovariables:0.13.7]:::github
   me[mecano:17-0.19.3]:::github
   meyv[mecano-yovariables:17-0.19.3]:::github
   im0[ihmc-messager:0.2.0]:::github
   im1[ihmc-messager:0.2.1]:::github
   imjfx[ihmc-messager-javafx:0.2.1]:::github
   ijfx[ihmc-javafx-extensions:17-0.2.2]:::github
   ivc[ihmc-video-codecs:2.1.6]:::github
   irdl[ihmc-robot-data-logger:0.37.3]:::github
   irt[ihmc-realtime:1.7.x]:::github
   ijdl[ihmc-java-decklink-capture:0.4.0]:::github
   inll[ihmc-native-library-loader:2.0.x]:::github
   ros2[ros2-library:1.2.1]:::github
   ipub[ihmc-pub-sub:source]:::github
   rci[ros2-common-interfaces:source]:::github
   zed[zed-java-api:5.1.0]:::github
   lt[log-tools:0.6.x]:::github
   jfo[jfoenix:17-0.1.1]:::github

   jim[["jim*ModelImporterJFX (6 artifacts)<br/>republished InteractiveMesh.org"]]:::noRepo
   ocv[us.ihmc:opencv:* custom]:::noRepo
   jcpp[us.ihmc:javacpp:1.5.11-ihmc-2]:::noRepo

   ext_ejml[org.ejml:ejml-core / ejml-ddense:0.39]:::external
   ext_log4j["log4j-api/core/slf4j-impl, jackson, jansi"]:::external
   ext_misc["trove4j, jaxb-impl, commons-lang3, commons-io"]:::external
   ext_rdl["protobuf, jcommander, guava, snappy, lz4,<br/>zstd-jni, netty, jol-core, commons-text,<br/>jackson-* (databind/yaml/xml/properties),<br/>sshj, openblas, ffmpeg, gst1-java-core, JavaFX"]:::external

   eu --> ext_ejml
   eug --> eu
   euf --> eu
   euf --> eug
   eus --> eu
   eus --> eug
   eus --> ext_ejml
   eufs --> eu
   eufs --> eug
   eufs --> eus
   eufs --> euf

   ic --> ext_misc
   ic --> lt
   lt --> ext_log4j

   iy --> eu
   iy --> euf
   iy --> ic
   iy --> ext_misc

   me --> eu
   me --> euf
   me --> eug
   me --> ext_ejml
   meyv --> me
   meyv --> iy

   im0 --> ic
   im0 --> lt
   im0 --> ext_misc
   im1 --> ic
   im1 --> lt
   im1 --> ext_misc
   imjfx --> im1

   ijfx --> eu
   ivc --> ext_rdl

   irt --> inll
   ijdl --> inll

   ipub --> inll
   ipub --> eu
   ipub --> ic
   ipub --> lt
   ipub --> jcpp
   rci --> eug
   rci --> ipub
   ros2 --> ipub
   ros2 --> irt
   ros2 --> ic
   ros2 --> rci

   zed --> inll
   zed --> ext_rdl

   irdl --> eu
   irdl --> ivc
   irdl --> irt
   irdl --> ijdl
   irdl --> ros2
   irdl --> ic
   irdl --> iy
   irdl --> me
   irdl --> ocv
   irdl --> zed
   irdl --> ext_rdl

   jfo --> ext_rdl
```

## 4. Notes

- `us.ihmc:scs2-definition:17-0.32.0` is referenced by `ihmc-robot-data-logger`
  as a published artifact. In this workspace it is not re-traversed because the
  same module is the local source-set node `scs2-definition`.
- `us.ihmc:javacpp:1.5.11-ihmc-2` and `us.ihmc:opencv:*-ihmc` are custom IHMC
  builds hosted on `robotlabfiles.ihmc.us`, not on `github.com/ihmcrobotics`,
  and are therefore not traversed.
- The six `us.ihmc:jim*ModelImporterJFX` artifacts are republications of the
  third-party InteractiveMesh.org JavaFX 3D model importers; no source
  repository exists under `ihmcrobotics`, so they are leaves.
