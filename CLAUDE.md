This is PipePipe's repo.
PipePipe is a hard fork of NewPipe, doesn't share same structure with NewPipe.
if talking about extractor, the files are in ../PipePipeExtractor

When modifying, always keep minimum changes. NEVER add comments.

Never read the following files fully, as they contain 3000-5000+ lines. only read needed lines.
./app/src/main/java/org/schabi/newpipe/download/DownloadDialog.java
./app/src/main/java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.java
./app/src/main/java/org/schabi/newpipe/fragments/list/search/SearchFragment.java
./app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java
./app/src/main/java/org/schabi/newpipe/local/feed/FeedFragment.kt 
./app/src/main/java/org/schabi/newpipe/player/datasource/YoutubeHttpDataSource.java 
./app/src/main/java/org/schabi/newpipe/player/Player.java 
./app/src/main/java/us/shandian/giga/ui/adapter/MissionAdapter.java

... and all the string.xml

when adding new setting keys or strings, always append to the end.
