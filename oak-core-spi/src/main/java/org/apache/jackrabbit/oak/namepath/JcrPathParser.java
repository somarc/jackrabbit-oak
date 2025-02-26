/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.namepath;

/**
 * TODO document
 */
public final class JcrPathParser {

    // constants for parser
    private static final int STATE_PREFIX_START = 0;
    private static final int STATE_PREFIX = 1;
    private static final int STATE_NAME_START = 2;
    private static final int STATE_NAME = 3;
    private static final int STATE_INDEX = 4;
    private static final int STATE_INDEX_END = 5;
    private static final int STATE_DOT = 6;
    private static final int STATE_DOTDOT = 7;
    private static final int STATE_URI = 8;

    private static final char EOF = (char) -1;

    private JcrPathParser() {
    }

    public interface Listener extends JcrNameParser.Listener {
        boolean root();
        boolean current();
        boolean parent();
    }

    private static final class PathAwareListener implements Listener {

        private final Listener listener;
        private final String jcrPath;

        private PathAwareListener(Listener listener, String jcrPath) {
            this.listener = listener;
            this.jcrPath = jcrPath;
        }

        @Override
        public void error(String message) {
            listener.error("'" + jcrPath + "' is not a valid path. " +  message);
        }

        @Override
        public boolean name(String name, int index) {
            return listener.name(name, index);
        }

        @Override
        public boolean root() {
            return listener.root();
        }

        @Override
        public boolean current() {
            return listener.current();
        }

        @Override
        public boolean parent() {
            return listener.parent();
        }
    }

    public static boolean parse(String jcrPath, Listener listener) {
        // check for length
        int len = jcrPath == null ? 0 : jcrPath.length();

        // shortcut for root path
        if (len == 1 && jcrPath.charAt(0) == '/') {
            listener.root();
            return true;
        }

        // short cut for empty path
        if (len == 0) {
            return true;
        }

        // check if absolute path
        int pos = 0;
        if (jcrPath.charAt(0) == '/') {
            if (!listener.root()) {
                return false;
            }
            pos++;
        }

        // parse the path
        int state = STATE_PREFIX_START;

        int lastPos = pos;
        String name = null;

        int index = 0;
        boolean wasSlash = false;

        final PathAwareListener pathAwareListener = new PathAwareListener(listener, jcrPath);

        while (pos <= len) {
            char c = pos == len ? EOF : jcrPath.charAt(pos);
            pos++;

            switch (c) {
                case '/':
                case EOF:
                    if (state == STATE_PREFIX_START && c != EOF) {
                        pathAwareListener.error("Double slash '//' not allowed.");
                        return false;
                    }
                    if (state == STATE_PREFIX
                            || state == STATE_NAME
                            || state == STATE_INDEX_END) {

                        // eof path element
                        if (name == null) {
                            if (wasSlash) {
                                pathAwareListener.error("Trailing slashes not allowed in prefixes and names.");
                                return false;
                            }
                            name = jcrPath.substring(lastPos, pos - 1);
                        }

                        if (!JcrNameParser.parse(name, pathAwareListener, index)) {
                            return false;
                        }
                        state = STATE_PREFIX_START;
                        lastPos = pos;
                        name = null;
                        index = 0;
                    } else if (state == STATE_DOT) {
                        if (!pathAwareListener.current()) {
                            return false;
                        }
                        lastPos = pos;
                        state = STATE_PREFIX_START;
                    } else if (state == STATE_DOTDOT) {
                        if (!pathAwareListener.parent()) {
                            return false;
                        }
                        lastPos = pos;
                        state = STATE_PREFIX_START;
                    } else if (state == STATE_NAME_START) {
                        pathAwareListener.error("Local name must not be empty.");
                        return false;
                    } else if (state == STATE_URI && c == EOF) {
                        // we reached EOF without finding the closing curly brace '}'
                        pathAwareListener.error("Missing '}'.");
                        return false;
                    } else if (state != STATE_URI
                            && !(state == STATE_PREFIX_START && c == EOF)) { // ignore trailing slash
                        pathAwareListener.error("'" + c + "' not allowed in name.");
                        return false;
                    }
                    break;

                case '.':
                    if (state == STATE_PREFIX_START) {
                        state = STATE_DOT;
                    } else if (state == STATE_DOT) {
                        state = STATE_DOTDOT;
                    } else if (state == STATE_DOTDOT) {
                        state = STATE_PREFIX;
                    } else if (state == STATE_INDEX_END) {
                        pathAwareListener.error("'" + c + "' not valid after index. '/' expected.");
                        return false;
                    }
                    break;

                case ':':
                    if (state == STATE_PREFIX_START) {
                        pathAwareListener.error("Prefix must not be empty.");
                        return false;
                    } else if (state == STATE_PREFIX) {
                        if (wasSlash) {
                            pathAwareListener.error("Trailing slashes not allowed in prefixes and names.");
                            return false;
                        }
                        state = STATE_NAME_START;
                        // don't reset the lastPos/pos since prefix+name are passed together to the NameResolver
                    } else if (state != STATE_URI) {
                        pathAwareListener.error("'" + c + "' not allowed in name.");
                        return false;
                    }
                    break;

                case ']':
                    if (state == STATE_INDEX) {
                        try {
                            index = Integer.parseInt(jcrPath.substring(lastPos, pos - 1));
                        } catch (NumberFormatException e) {
                            pathAwareListener.error("NumberFormatException in index: " +
                                    jcrPath.substring(lastPos, pos - 1));
                            return false;
                        }
                        if (index < 0) {
                            pathAwareListener.error("Index number invalid: " + index);
                            return false;
                        }
                        state = STATE_INDEX_END;
                    } else {
                        pathAwareListener.error("'" + c + "' not allowed in name.");
                        return false;
                    }
                    break;

                case '*':
                case '|':
                    pathAwareListener.error("'" + c + "' not allowed in name.");
                    return false;
                case '{':
                    if (state == STATE_PREFIX_START && lastPos == pos-1) {
                        // '{' marks the start of a uri enclosed in an expanded name
                        // instead of the usual namespace prefix, if it is
                        // located at the beginning of a new segment and a '}' will follow.
                        state = jcrPath.indexOf('}', pos) == -1 ? STATE_NAME : STATE_URI;
                    } else if (state == STATE_PREFIX || state == STATE_NAME_START || state == STATE_DOT || state == STATE_DOTDOT) {
                        // otherwise it's part of the local name
                        state = STATE_NAME;
                    }
                    break;

                case '}':
                    if (state == STATE_URI) {
                        state = jcrPath.indexOf(':', jcrPath.lastIndexOf('{', pos)) == -1 ?
                                STATE_NAME : STATE_NAME_START;
                    } else if (state == STATE_PREFIX_START || state == STATE_DOT || state == STATE_DOTDOT) {
                        state = STATE_PREFIX;
                    } else if (state == STATE_NAME_START || state == STATE_PREFIX) {
                        state = STATE_NAME;
                    } else if (state == STATE_INDEX_END) {
                        pathAwareListener.error("'" + c + "' not valid after index. '/' expected.");
                        return false;
                    }
                    break;

                case '[':
                    if (state == STATE_PREFIX || state == STATE_NAME) {
                        if (wasSlash) {
                            pathAwareListener.error("Trailing slashes not allowed in prefixes and names.");
                            return false;
                        }
                        state = STATE_INDEX;
                        name = jcrPath.substring(lastPos, pos - 1);
                        lastPos = pos;
                    }
                    else if (state == STATE_PREFIX_START || state == STATE_DOT || state == STATE_DOTDOT) {
                        state = STATE_PREFIX;
                    } else if (state == STATE_NAME_START) {
                        state = STATE_NAME;
                    } else if (state == STATE_INDEX_END) {
                        pathAwareListener.error("'" + c + "' not valid after index. '/' expected.");
                        return false;
                    }
                    break;

                default:
                    if (state == STATE_PREFIX_START || state == STATE_DOT || state == STATE_DOTDOT) {
                        state = STATE_PREFIX;
                    } else if (state == STATE_NAME_START) {
                        state = STATE_NAME;
                    } else if (state == STATE_INDEX_END) {
                        pathAwareListener.error("'" + c + "' not valid after index. '/' expected.");
                        return false;
                    }
            }
            wasSlash = c == '/';
        }
        return true;
    }

    public static boolean validate(String jcrPath) {
        Listener listener = new Listener() {
            int depth;
            boolean hasRoot;
            @Override
            public boolean root() {
                if (hasRoot) {
                    return false;
                }
                else {
                    hasRoot = true;
                    return true;
                }
            }

            @Override
            public boolean current() {
                return true;
            }

            @Override
            public boolean parent() {
                depth--;
                return !hasRoot || depth >= 0;
            }

            @Override
            public void error(String message) {
            }

            @Override
            public boolean name(String name, int index) {
                depth++;
                return true;
            }

        };
        return parse(jcrPath, listener);
    }
}
