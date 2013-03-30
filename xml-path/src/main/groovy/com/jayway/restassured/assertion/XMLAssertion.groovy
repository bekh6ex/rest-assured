/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jayway.restassured.assertion

import com.jayway.restassured.internal.assertion.Assertion
import com.jayway.restassured.internal.path.xml.NodeChildrenImpl
import com.jayway.restassured.internal.path.xml.NodeImpl
import com.jayway.restassured.path.xml.element.NodeChildren
import groovy.util.slurpersupport.*

import static com.jayway.restassured.internal.assertion.AssertionSupport.*

class XMLAssertion implements Assertion {
    private static final String DOT = "."
    private static final String EXPLICIT_LIST_CONVERSION = 'list()'
    String key;

    /* Matches fragment such as children() or size(2) */
    private def isInvocationFragment = ~/.*\(\d*\)|.*(\{|\}).*/

    private def fragments

    /**
     * @param object The object to get the result from
     * @param shouldConvertToJavaObject Should the result be converted to Java Object or should we retain the returned Groovy object
     * @param rootEvaluation True if we're evaluating from a root, false if start node is a child node.
     */
    def Object getResult(Object object, boolean shouldConvertToJavaObject, boolean rootEvaluation) {
        if (rootEvaluation) {
            key = key?.startsWith(DOT) ? key.substring(1) : key
        }
        key = escapePath(key, minus(), attributeGetter(), doubleStar())
        def indexOfDot = key.indexOf(".")
        def baseString
        def evaluationString
        def isRootOnly = rootEvaluation ? (indexOfDot < 0) : false
        if (!isRootOnly) {
            fragments = key.split("\\.");
            def firstFragment = fragments[0];
            if (isDoubleStarFragment(firstFragment) || !isPathFragment(firstFragment)) {
                evaluationString = key.startsWith(DOT) ?: DOT + key; // Add a dot if needed because the first path fragment is actually a method invocation
                baseString = firstFragment
            } else if (rootEvaluation) {
                evaluationString = key.substring(indexOfDot);
                baseString = key.substring(0, indexOfDot)
            } else {
                evaluationString = key.startsWith(".") ? key : "." + key;
                baseString = "";
            }
        } else {
            evaluationString = "";
            baseString = key;
        }

        def result;
        def rootObjectVariableName = "restAssuredXmlRootObject"
        try {
            result = Eval.me(rootObjectVariableName, object, "$rootObjectVariableName$evaluationString")
        } catch (Exception e) {
            def errorMessage = e.getMessage();
            if (errorMessage.startsWith("No signature of method:")) {
                errorMessage = "Path $key is invalid."
            } else {
                errorMessage = e.getMessage().replace("startup failed:", "Invalid path:").replace(rootObjectVariableName, generateWhitespace(rootObjectVariableName.length() - baseString.length()) + baseString)
            }
            throw new IllegalArgumentException(errorMessage);
        }
        def convertedObject = shouldConvertToJavaObject ? convertToJavaObject(result) : result
        return preventTreatingRootObjectAsAList(convertedObject)
    }

    def Object getResult(Object object) {
        return getResult(object, true, true)
    }

    def Object getChildResultAsJavaObject(Object object) {
        return getResult(object, true, false)
    }

    private def isDoubleStarFragment(String fragment) {
        def trimmed = fragment.trim()
        return trimmed == "**" || trimmed == "'**'"
    }

    private def preventTreatingRootObjectAsAList(javaObject) {
        if (javaObject instanceof List && javaObject.size() == 1 && fragments[-1] != EXPLICIT_LIST_CONVERSION) {
            javaObject = javaObject.get(0)
        }
        return javaObject
    }

    boolean isPathFragment(String fragment) {
        return !isInvocationFragment.matcher(fragment).matches()
    }

    private def convertToJavaObject(result) {
        def returnValue;
        if (Attributes.class.isAssignableFrom(result.getClass())) {
            returnValue = toJavaObject(result, true, false)
        } else if (result instanceof Node) {
            returnValue = nodeToJavaObject(result)
        } else if (result instanceof FilteredNodeChildren) {
            returnValue = toJavaObject(result, false, true)
        } else if (result instanceof NodeChild) {
            returnValue = nodeToJavaObject(result)
        } else if (result instanceof GPathResult) {
            returnValue = toJavaObject(result, false, false)
        } else if (result instanceof List) {
            returnValue = handleList(result)
        } else {
            returnValue = result;
        }

        return returnValue
    }

    private def handleList(List result) {
        if (result.size() == 1 && fragments[-1] != EXPLICIT_LIST_CONVERSION) {
            return convertToJavaObject(result.get(0))
        } else {
            for (int i = 0; i < result.size(); i++) {
                result.set(i, convertToJavaObject(result.get(i)))
            }
        }

        result
    }

    private def nodeToJavaObject(node) {
        if (node.getClass().getMethods().find { it.name == "size" && it.parameterTypes.size() == 0} != null) {
            if (node.size() == 1 && !hasChildren(node, false)) {
                return node.text()
            }
        }

        def nodeImpl = new NodeImpl(name: node.name(), groovyNode: node)
        addAttributes(nodeImpl, node)
        for (Object child : node.children()) {
            if (child instanceof Node) {
                def object = convertToJavaObject(child)
                nodeImpl.children << object
            } else {
                nodeImpl.value = child
            }
        }
        nodeImpl
    }

    private def addAttributes(nodeImpl, node) {
        def attributes = node.attributes();
        nodeImpl.attributes = convertToJavaObject(attributes)
    }

    private boolean shouldBeTreatedAsList(child) {
        def firstGrandChild = child.children().get(0);
        return firstGrandChild instanceof Node;
    }

    private def toJavaObject(nodes, isAttributes, forceList) {
        if (nodes.size() == 1 && !hasChildren(nodes, isAttributes)) {
            return nodes.text()
        } else {
            return toJavaList(nodes, isAttributes, forceList)
        }
    }

    private boolean hasChildren(nodes, isAttributes) {
        if (isAttributes) {
            return false;
        }
        return !nodes.children().isEmpty()
    }

    private def toJavaList(nodes, isAttributes, forceList) {
        def nodeList = forceList ? [] : new NodeChildrenImpl(groovyNodes: nodes)
        if (isAttributes) {
            def temp = []
            nodes.each {
                CharArrayWriter caw = new CharArrayWriter();
                it.writeTo(caw);
                caw.close();
                temp << caw.toString()
            }
            return temp
        } else {
            nodes.nodeIterator().each {
                def object = convertToJavaObject(it)
                nodeList << object
            }
        }
        nodeList
    }

    def String description() {
        return "XML path"
    }
}

class XmlEntity {
    def children
    def attributes

}