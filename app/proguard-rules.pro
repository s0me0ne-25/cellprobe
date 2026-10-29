# Project-specific R8 rules. Most libraries ship their own consumer rules.

# zstd-kmp ships no consumer rules; its JNI code binds native methods and
# fields by name, so the classes must keep their names and members.
-keep class com.squareup.zstd.** { *; }
