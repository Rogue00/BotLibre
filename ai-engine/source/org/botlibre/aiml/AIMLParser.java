/******************************************************************************
 *
 *  Copyright 2014 Paphus Solutions Inc.
 *
 *  Licensed under the Eclipse Public License, Version 1.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/
package org.botlibre.aiml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.botlibre.BotException;
import org.botlibre.api.knowledge.Network;
import org.botlibre.api.knowledge.Relationship;
import org.botlibre.api.knowledge.Vertex;
import org.botlibre.knowledge.BinaryData;
import org.botlibre.knowledge.Primitive;
import org.botlibre.self.SelfCompiler;
import org.botlibre.self.SelfParseException;
import org.botlibre.thought.language.Language;
import org.botlibre.util.Utils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;


/**
 * Utility class for printing the Self programming model.
 * Self is the language that Bot programs herself in.
 */
public class AIMLParser {
	public static int MAX_FILE_SIZE = 10000000; // 10meg
	public static int PAGE = 100;
	public static int MAX_IDENTIFIER = 100;

	public static Set<String> htmlTags = new HashSet<String>(Arrays.asList(new String[] {
			"p", "ul", "ol", "li", "b", "em", "i", "strong", "code", "br", "a", "img", "video", "audio",
			"table", "tr", "td", "tablebody", "font", "tablehead", "th"}));

	public static List<String> topicChildren = Arrays.asList(new String[] {"category", "#text"});
	public static List<String> categoryChildren = Arrays.asList(new String[] {"pattern", "that", "template", "topic", "#text"});
	public static List<String> patternChildren =
			Arrays.asList(new String[] {"#text","bot", "name", "get", "set"});
	public static Set<String> templateChildren =
			new HashSet<String>(Arrays.asList(new String[] {
					"p", "ul", "ol", "li", "b", "em", "i", "strong", "code", "br", "a", "img", "video", "audio",
					"table", "tr", "td", "tablebody", "font", "tablehead", "th",
					"#text",
					"bot", "name", "get", "set", "value", "index", "map", "new",
					"think", "srai", "sraix", "sr", "random", "condition", "loop", "set",
					"star", "input", "that", "thatstar", "topicstar", "request", "response",
					"date", "interval", "size", "version", "id", "vocabulary", "program",
					"person", "person2", "gender", "uppercase", "lowercase", "formal", "sentence",
					"learn", "eval",
					"explode", "normalize", "denormalize"}));
	public static Set<String> attributeNodes =
			new HashSet<String>(Arrays.asList(new String[] {
					"name", "index", "value", "var", "botname", "botid", "server", "service", "limit", "apikey", "default", "hint"}));
	protected static AIMLParser parser = new AIMLParser();

	public static AIMLParser parser() {
		return parser;
	}
	
	/**
	 * Get the contents of the URL to a .aiml file and parse it.
	 */
	public Vertex parseAIML(URL url, boolean parseAsStateMachine, boolean createStates, boolean pin, boolean indexStatic,
				Vertex stateMachine, String encoding, Network network) {
		try {
			String text = Utils.loadTextFile(Utils.openStream(url), encoding, MAX_FILE_SIZE);
			return parseAIML(text, parseAsStateMachine, createStates, pin, indexStatic, stateMachine, network);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Get the contents of the URL to a .aiml file and parse it.
	 */
	public Vertex parseAIML(File file, boolean parseAsStateMachine, boolean createStates, boolean pin, boolean indexStatic,
				Vertex stateMachine, String encoding, Network network) {
		try {
			String text = Utils.loadTextFile(new FileInputStream(file), encoding, MAX_FILE_SIZE);
			return parseAIML(text, parseAsStateMachine, createStates, pin, indexStatic, stateMachine, network);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Parse the template into a forumla defined in the network.
	 */
	public Vertex parseAIMLTemplate(String code, Network network) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder parser = factory.newDocumentBuilder();
			StringReader reader = new StringReader(code);
			InputSource source = new InputSource(reader);
			Document document = parser.parse(source);
			Element root = document.getDocumentElement();

			String template = getTemplate(root, false, false, new boolean[1], false, false, false, network);
			Vertex formula = network.createFormula(template);
			return formula;
		} catch (Exception exception) {
			network.getBot().log(this, exception);
			return null;
		}
	}
	
	/**
	 * Parse the code into a vertex state machine defined in the network.
	 */
	public Vertex parseAIML(String code, boolean parseAsStateMachine, boolean createStates, boolean pin, boolean indexStatic, Vertex stateMachine, Network network) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder parser = factory.newDocumentBuilder();
			StringReader reader = new StringReader(code);
			InputSource source = new InputSource(reader);
			Document document = parser.parse(source);
			Element root = document.getDocumentElement();
			Map<String, Vertex> cache = new HashMap<String, Vertex>();

			Vertex sentenceState = null;
			if (parseAsStateMachine) {
				if (createStates) {
					// Get first case that gets sentence from input.
					List<Vertex> instructions = stateMachine.orderedRelations(Primitive.DO);
					if (instructions != null) {
						for (Vertex instruction : instructions) {
							if (instruction.instanceOf(Primitive.CASE)) {
								Vertex variable = instruction.getRelationship(Primitive.CASE);
								if ((variable != null) && variable.isVariable() && variable.hasRelationship(Primitive.INPUT)) {
									sentenceState = instruction.getRelationship(Primitive.GOTO);
									break;
								}
							}
						}
					}
					if (sentenceState == null) {
						Vertex sentenceCase = network.createInstance(Primitive.CASE);
						sentenceCase.setName("c_sentence");
						Vertex inputVariable = network.createVertex(Primitive.INPUT_VARIABLE);
						sentenceCase.addRelationship(Primitive.CASE, inputVariable);
						sentenceState = network.createInstance(Primitive.STATE);
						sentenceState.setName("s_sentence");
						sentenceCase.addRelationship(Primitive.GOTO, sentenceState);
						sentenceCase.addRelationship(Primitive.FOR, network.createVertex(Primitive.WORD));
						sentenceCase.addRelationship(Primitive.FOR, inputVariable.getRelationship(Primitive.INPUT));
						stateMachine.addRelationship(Primitive.DO, sentenceCase);
					}
				} else {
					sentenceState = stateMachine;
				}
			}
			List<Element> topics = getLocalElementsByTagName("topic", root);
			int count = 0;
			for (Element topic : topics) {
				String text = getNodeValue(topic, "name", "", false, false, false, new boolean[1], network);
				Vertex topicFilter = null;
				if (!isPattern(text)) {
					topicFilter = network.createSentence(text);
				} else {
					topicFilter = network.createPattern(text);
				}
				network.getBot().log(this, "Topic", Level.INFO, topicFilter);
				checkSupportedChildren(topic, topicChildren, network);
				List<Element> categories = getLocalElementsByTagName("category", topic);
				for (Element category : categories) {
					parseCategory(category, topicFilter, parseAsStateMachine, createStates, pin, indexStatic, sentenceState, cache, network);
					count++;
					if (count >= PAGE) {
						network.save();
						network.clear();
						count = 0;
						if (parseAsStateMachine) {
							stateMachine = network.createVertex(stateMachine);
							sentenceState = network.createVertex(sentenceState);
						}
					}
				}
			}
			List<Element> categories = getLocalElementsByTagName("category", root);
			for (Element category : categories) {
				parseCategory(category, null, parseAsStateMachine, createStates, pin, indexStatic, sentenceState, cache, network);
				count++;
				if (count >= PAGE) {
					network.save();
					network.clear();
					count = 0;
					if (parseAsStateMachine) {
						stateMachine = network.createVertex(stateMachine);
						sentenceState = network.createVertex(sentenceState);
					}
				}
			}
			network.save();
			network.getBot().log(this, "Compiled new AIML script", Level.INFO);
			return stateMachine;
		} catch (Exception exception) {
			network.getBot().log(this, exception);
			throw new BotException("Parsing error occurred - " + exception.toString(), exception);
		}
	}
	
	public void checkSupportedChildren(Element element, Collection<String> tags, Network network) {
		NodeList list = element.getChildNodes();
		for (int index2 = 0;  index2 < list.getLength(); index2++) {
			Node child = list.item(index2);
			String name = child.getNodeName().toLowerCase();
			if ((child.getNodeType() != Node.TEXT_NODE)
						&& (child.getNodeType() != Node.COMMENT_NODE) && !tags.contains(name)) {
				network.getBot().log(this, "Unsupported " + element.getNodeName() + " child", Level.WARNING, name, child.getTextContent());
			}
		}
	}
	
	public boolean isPattern(String text) {
		return (text.indexOf('*') != -1 || text.indexOf('_') != -1 || text.indexOf('^') != -1 || text.indexOf('#') != -1 || text.indexOf('$') != -1
				|| text.indexOf('{') != -1 || text.indexOf('}') != -1 || text.indexOf('[') != -1 || text.indexOf(']') != -1);
	}

	@SuppressWarnings("unchecked")
	public void parseCategory(Element category, Vertex topic, boolean parseAsStateMachine, boolean createStates, boolean pin, boolean indexStatic,
				Vertex sentenceState, Map<String, Vertex> cache, Network network) {
		checkSupportedChildren(category, categoryChildren, network);
		// <pattern>
		List<Element> patterns = getLocalElementsByTagName("pattern", category);
		if (patterns.isEmpty()) {
			network.getBot().log(this, "Missing pattern", Level.WARNING);
			return;
		}
		
		Element pattern = patterns.get(0);
		String text = getPattern(pattern, network).toLowerCase();
		Vertex question = null;
		boolean isPattern = false;
		boolean isStarStartPattern = false;
		boolean isStarEndPattern = false;
		boolean is_Pattern = false;
		boolean multiStar = false;
		boolean isDefault = false;
		int underscoreIndex = text.indexOf('_');
		int poundIndex = text.indexOf('#');
		int starIndex = text.indexOf('*');
		int hatIndex = text.indexOf('^');
		boolean isDollarPattern = text.indexOf('$') != -1;
		if (!isPattern(text)) {
			question = network.createSentence(text);
			pin =  pin || (parseAsStateMachine && indexStatic);
			if (pin) {
				question.setPinned(true);
			}
		} else {
			if (text.trim().length() == 1) {
				isDefault = true;
			}
			question = network.createPattern(text);
			if (pin) {
				question.setPinned(true);
			}
			isPattern = true;
			if (underscoreIndex != -1) {
				is_Pattern = true;
				if ((text.indexOf('_', underscoreIndex + 1) != -1) || starIndex != -1 || poundIndex != -1 || hatIndex != -1)  {
					multiStar = true;
				}
			} else if (starIndex != -1) {
				if ((text.indexOf('*', starIndex + 1) != -1) || underscoreIndex != -1 || poundIndex != -1 || hatIndex != -1)  {
					multiStar = true;
				}				
			} else if (poundIndex != -1) {
				if ((text.indexOf('#', poundIndex + 1) != -1) || underscoreIndex != -1 || starIndex != -1 || hatIndex != -1)  {
					multiStar = true;
				}				
			} else if (hatIndex != -1) {
				if ((text.indexOf('^', hatIndex + 1) != -1) || underscoreIndex != -1 || starIndex != -1 || poundIndex != -1)  {
					multiStar = true;
				}				
			}
			if (text.startsWith("*")) {
				isStarStartPattern = true;
			}
			if (text.endsWith("*") && (text.indexOf('*') < (text.length() - 1))) {
				isStarEndPattern = false;
			}
		}
		network.getBot().log(this, "Pattern", Level.INFO, question);
		checkSupportedChildren(pattern, patternChildren, network);
		Vertex that = null;
		// <topic>
		List<Element> topics = getLocalElementsByTagName("topic", category);
		if (!topics.isEmpty()) {
			for (Element child : topics) {
				text = getPattern(child, network).toLowerCase();
				if (!text.isEmpty()) {
					if (isPattern(text)) {
						topic = network.createPattern(text);
					} else {
						topic = network.createSentence(Utils.reduce(text), false, true);
					}
					if (pin) {
						topic.setPinned(true);
					}
					network.getBot().log(this, "Topic", Level.INFO, topic);
					checkSupportedChildren(child, Collections.EMPTY_LIST, network);
				}
			}
		}
		// <that>		
		List<Element> thats = getLocalElementsByTagName("that", category);
		if (!thats.isEmpty()) {
			for (Element child : thats) {
				text = getPattern(child, network).toLowerCase();
				that = network.createPattern(text);
				if (pin) {
					that.setPinned(true);
				}
				network.getBot().log(this, "That", Level.INFO, that);
				checkSupportedChildren(child, Collections.EMPTY_LIST, network);
			}
		} else {
			that = null;
		}
		// <template>
		List<Element> templates = getLocalElementsByTagName("template", category);
		if (templates.isEmpty()) {
			network.getBot().log(this, "Missing template", Level.WARNING);
			return;
		}
		Element template = templates.get(0);
		checkSupportedChildren(template, templateChildren, network);
		Vertex equation = null;
		if (parseAsStateMachine && (isPattern || !indexStatic)) {
			equation = network.createInstance(Primitive.CASE);
			equation.addRelationship(Primitive.PATTERN, question);
			if (that != null) {
				equation.addRelationship(Primitive.THAT, that);
			}
		}
		boolean[] srai = new boolean[1];
		String templateText = getTemplate(template, false, multiStar, srai, false, false, false, network);
		boolean isFormula = templateText.startsWith("Formula:");
		Vertex response = null;
		if (isFormula) {
			response = cache.get(templateText);
			if (response == null) {
				response = network.createFormula(templateText);
				if (pin) {
					SelfCompiler.getCompiler().pin(response);
				}
				cache.put(templateText, response);
			}
		} else {
			response = network.createVertex(templateText);
			if (pin) {
				response.setPinned(true);
			}
			response.addRelationship(Primitive.INSTANTIATION, Primitive.SENTENCE);
		}
		network.getBot().log(this, "Template", Level.INFO, response);
		if (parseAsStateMachine && (isPattern || !indexStatic)) {
			equation.addRelationship(Primitive.TEMPLATE, response);
			if (topic != null) {
				equation.addRelationship(Primitive.TOPIC, topic);
			}
		} else {
			Relationship relationship = null;
			// Add * to default responses.
			if (isDefault && !createStates) {
				Vertex language = network.createVertex(Language.class);
				relationship = language.addRelationship(Primitive.RESPONSE, response);
			} else {
				relationship = question.addRelationship(Primitive.RESPONSE, response);
				question.associateAll(Primitive.WORD, question, Primitive.QUESTION);
			}
			response.addRelationship(Primitive.QUESTION, question);
			if (topic != null) {
				Vertex meta = network.createMeta(relationship);
				meta.addRelationship(Primitive.TOPIC, topic);
				meta.addRelationship(Primitive.REQUIRE, Primitive.TOPIC);
			}
			if (that != null) {
				Vertex meta = network.createMeta(relationship);
				meta.addRelationship(Primitive.PREVIOUS, that);
				meta.addRelationship(Primitive.REQUIRE, Primitive.PREVIOUS);
			}
		}
		if (parseAsStateMachine && (isPattern || !indexStatic)) {
			Vertex state = sentenceState;
			if (createStates) {
				state = createState(question, sentenceState, network);
			}
			if (isPattern) {
				if (that != null || topic != null) {
					state.addRelationship(Primitive.DO, equation, 1);
				} else {
					if (isDollarPattern) {
						state.addRelationship(Primitive.DO, equation, 2);					
					} else if (is_Pattern) {
						state.addRelationship(Primitive.DO, equation, 3);					
					} else if (isStarStartPattern) {
						state.addRelationship(Primitive.DO, equation, 8);
					} else if (isStarEndPattern) {
						state.addRelationship(Primitive.DO, equation, 7);
					} else {
						state.addRelationship(Primitive.DO, equation, 6);					
					}
				}
			} else {
				if (that != null || topic != null) {
					state.addRelationship(Primitive.DO, equation, 0);
				}  else if (srai[0]) {
					state.addRelationship(Primitive.DO, equation, 5);
				} else {
					state.addRelationship(Primitive.DO, equation, 4);
				}
			}
		}
	}
	
	/**
	 * Create a left child state node for the pattern.
	 */
	public Vertex createState(Vertex pattern, Vertex parent, Network network) {
		Vertex currentState = parent;
		Collection<Relationship> words = pattern.orderedRelationships(Primitive.WORD);
		if (words != null) {
			StringWriter pathWriter = new StringWriter();
			//int index = 0;
			for (Relationship word : words) {
				/*index++;
				// Need to only go to previous state.
				if (index >= words.size()) {
					return currentState;
				}*/
				Vertex value = word.getTarget();
				if (value.is(Primitive.WILDCARD)) {
					pathWriter.write("_star");
				} else if (value.is(Primitive.UNDERSCORE)) {
					value = network.createVertex(Primitive.UNDERSCORE);
					pathWriter.write("_underscore");
				} else if (value.is(Primitive.POUNDWILDCARD)) {
					value = network.createVertex(Primitive.POUNDWILDCARD);
					pathWriter.write("_pound");
				} else if (value.is(Primitive.HATWILDCARD)) {
					value = network.createVertex(Primitive.HATWILDCARD);
					pathWriter.write("_hat");
				} else {
					pathWriter.write("_");
					pathWriter.write(value.printString());
				}
				List<Vertex> instructions = currentState.orderedRelations(Primitive.DO);
				Vertex caseMatch = null;
				if (instructions != null) {
					// Find the case that matches.
					for (Vertex instruction : instructions) {
						if (instruction.instanceOf(Primitive.CASE)) {
							Vertex caseValue = instruction.getRelationship(Primitive.CASE);
							if (caseValue == value) {
								caseMatch = instruction;
								break;
							}
						}
					}
				}
				if (caseMatch == null) {
					// Clear cache if byte code.
					if (currentState.getData() instanceof BinaryData) {
						BinaryData data = (BinaryData)currentState.getData();
						data.setCache(null);
						data = (BinaryData)network.getBot().memory().getLongTermMemory().findData(data);
						if (data != null) {
							data.setCache(null);
						}
					}					
					caseMatch = network.createInstance(Primitive.CASE);
					caseMatch.setName("c" + caseMatch.getId() + "_" + value.printString());
					caseMatch.addRelationship(Primitive.CASE, value);
					Vertex newState = network.createInstance(Primitive.STATE);
					newState.setName("s" + newState.getId() + Utils.compress(pathWriter.toString(), MAX_IDENTIFIER));
					caseMatch.addRelationship(Primitive.GOTO, newState);
					if (word.getTarget().is(Primitive.UNDERSCORE) || word.getTarget().is(Primitive.POUNDWILDCARD)) {
						currentState.addRelationship(Primitive.DO, caseMatch, 3);
						newState.addRelationship(Primitive.DO, caseMatch, 10);
					} else if (word.getTarget().is(Primitive.WILDCARD) || word.getTarget().is(Primitive.HATWILDCARD)) {
						currentState.addRelationship(Primitive.DO, caseMatch, 5);
						newState.addRelationship(Primitive.DO, caseMatch, 10);
					} else {
						if (word.hasMeta() && word.getMeta().hasRelationship(Primitive.TYPE, Primitive.PRECEDENCE)) {
							currentState.addRelationship(Primitive.DO, caseMatch, 2);
						} else {
							currentState.addRelationship(Primitive.DO, caseMatch, 4);							
						}
					}
					currentState = newState;
				} else {
					Vertex state = caseMatch.getRelationship(Primitive.GOTO);
					if (state == null) {
						state = network.createInstance(Primitive.STATE);
						state.setName("s" + state.getId() + Utils.compress(pathWriter.toString(), MAX_IDENTIFIER));
						caseMatch.addRelationship(Primitive.GOTO, state);						
					}
					currentState = state;
				}
			}
		}
		return currentState;
	}
	
	public List<Element> getLocalElementsByTagName(String tag, Element element) {
		NodeList children = element.getChildNodes();
		List<Element> local = new ArrayList<Element>();
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (child.getNodeName().equals(tag)) {
				local.add((Element)child);
			}
		}
		return local;
	}
	
	public String getPattern(Element element, Network network) {
		StringWriter writer = new StringWriter();
		NodeList list = element.getChildNodes();
		for (int index = 0; index < list.getLength(); index++) {
			Node child = list.item(index);
			String name = child.getNodeName().toLowerCase();
			if (child.getNodeType() == Node.TEXT_NODE) {
				writer.write(child.getNodeValue());
			} else if (name.equals("name") || name.equals("index") || name.equals("value")  || name.equals("var")) {
				continue;
			} else if (child instanceof Element) {
				writer.write("{");
				appendPatternCode((Element)child, false, new boolean[1], writer, network);
				writer.write("}");
			}
		}
		return writer.toString().trim();
	}
	
	public void appendNestedString(Element child, boolean multiStar, boolean[] srai, StringWriter writer, Network network) {
		String nested = getTemplate((Element)child, true, multiStar, srai, true, true, false, network);
		writer.write(nested);
	}
	
	public boolean appendNestedText(Element child, boolean multiStar, boolean[] srai, StringWriter writer, Network network) {
		String nested = getTemplate((Element)child, true, multiStar, srai, false, false, false, network);
		boolean isNestedFormula = nested.startsWith("Formula:");
		if (isNestedFormula) {
			nested = nested.substring("Formula:\"".length(), nested.length() - 1);
		}
		writer.write(nested);
		return isNestedFormula;
	}
	
	public boolean appendHTML(String tag, Element child, boolean multiStar, boolean[] srai, boolean isFormula, StringWriter writer, Network network) {
		writer.write("<");
		writer.write(tag);
		NamedNodeMap attributes = child.getAttributes();
		for (int index = 0; index < attributes.getLength(); index++) {
			Node attribute = attributes.item(index);
			writer.write(" ");
			writer.write(attribute.getNodeName());
			writer.write("=\"\"");
			writer.write(attribute.getNodeValue());
			writer.write("\"\"");
		}
		if (!child.hasChildNodes()) {
			writer.write("/>");
		} else {
			writer.write(">");
			if (appendNestedText((Element)child, multiStar, srai, writer, network)) {
				isFormula = true;						
			}
			writer.write("</");
			writer.write(tag);
			writer.write(">");
		}
		return isFormula;
	}
	
	public String getTemplate(Element element, boolean quote, boolean multiStar, boolean[] srai, boolean flattenFormulas, boolean addQuotes, boolean pattern, Network network) {
		boolean isFormula = false;
		boolean nameNode = false;
		StringWriter writer = new StringWriter();
		NodeList list = element.getChildNodes();
		for (int index = 0; index < list.getLength(); index++) {
			Node child = list.item(index);
			String name = child.getNodeName().toLowerCase();
			if (child.getNodeType() == Node.TEXT_NODE) {
				// TODO PERF
				String text = child.getNodeValue();
				if (text.contains("<")) {
					text = text.replace("<", "&lt;");
				}
				if (text.contains(">")) {
					text = text.replace(">", "&gt;");
				}
				if (text.contains("\t")) {
					text = text.replace("\t", " ");
				}
				if (text.contains("\r")) {
					text = text.replace("\r", " ");
				}
				if (text.contains("\n")) {
					text = text.replace("\n", " ");
				}
				if (text.contains("   ")) {
					text = text.replace("   ", " ");
				}
				if (text.contains("  ")) {
					text = text.replace("  ", " ");
				}
				if (text.contains("{")) {
					text = text.replace("{", "(");
				}
				if (text.contains("}")) {
					text = text.replace("}", ")");
				}
				if (text.contains("\"")) {
					text = text.replace("\"", "\"\"");
				}
				writer.write(text);
			} else if (attributeNodes.contains(name)) {
				nameNode = true;
				continue;
			} else if (htmlTags.contains(name)) {
				isFormula = appendHTML(name, (Element)child, multiStar, srai, isFormula, writer, network);
			} else if (child instanceof Element) {
				if (flattenFormulas && list.getLength() == 1 || (nameNode && list.getLength() == 2)) {
					writer.write("(");
					appendCode((Element)child, multiStar, srai, writer, network);
					writer.write(")");
					return writer.toString().trim();
				}
				writer.write("{");
				appendCode((Element)child, multiStar, srai, writer, network);
				writer.write("}");
				isFormula = true;
			}
		}
		String text = writer.toString().trim();
		if (isFormula && !pattern) {
			text = "Formula:\"" + text + "\"";
		} else {
			if (!quote && text.contains("\"")) {
				text = text.replace("\"\"", "\"");
			}
			if (flattenFormulas) {
				text = "\"" + text + "\"";
			}
		}
		return text;
	}
	
	public String getNodeValue(Element node, String value, String defaulValue, boolean primitive, boolean quote, boolean multiStar, boolean[] srai, Network network) {
		List<Element> valueElements = getLocalElementsByTagName(value, node);
		if (valueElements != null && valueElements.size() > 0) {
			StringWriter writer = new StringWriter();
			if (primitive) {
				writer.write("(primitive ");
			}
			appendNestedString((Element)valueElements.get(0), multiStar, srai, writer, network);
			if (primitive) {
				writer.write(")");
			}
			return writer.toString();
		}
		Node child = node.getAttributes().getNamedItem(value);
		if (child == null) {
			for (int index = 0; index < node.getAttributes().getLength(); index++) {
				Node attribute = node.getAttributes().item(index);
				if (attribute.getNodeName().equalsIgnoreCase(value)) {
					if (primitive) {
						return "#" + attribute.getNodeValue();
					} else if (quote) {
						return "\"" + attribute.getNodeValue() + "\"";
					} else {
						return attribute.getNodeValue();						
					}
				}
			}
			return defaulValue;
		}
		if (primitive) {
			return "#" + child.getNodeValue();
		} else if (quote) {
			return "\"" + child.getNodeValue() + "\"";
		} else {
			return child.getNodeValue();
		}
	}
	
	public void appendCode(Element child, boolean multiStar, boolean[] srai, StringWriter writer, Network network) {
		String name = child.getNodeName().toLowerCase();
		if (name.equals("bot")) {
			writer.write("get ");
			writer.write(getNodeValue(child, "name", "#name", true, false, multiStar, srai, network));
			writer.write(" from :target");
		} else if (name.equals("get")) {			
			writer.write("get ");
			String value = getNodeValue(child, "name", null, true, false, multiStar, srai, network);
			boolean isLocal = false;
			if (value == null) {
				value = getNodeValue(child, "var", null, true, false, multiStar, srai, network);
				if (value != null) {
					isLocal = true;
				} else {
					value = "#name";
				}
			}
			writer.write(value);
			if (isLocal) {
				writer.write(" from :input");				
			} else if (value.equalsIgnoreCase("#name")) {
				writer.write(" from :speaker");				
			} else {
				writer.write(" from :conversation");
			}
		} else if (name.equals("set")) {
			writer.write("set ");
			String value = getNodeValue(child, "name", null, true, false, multiStar, srai, network);
			boolean isLocal = false;
			if (value == null) {
				value = getNodeValue(child, "var", null, true, false, multiStar, srai, network);
				if (value != null) {
					isLocal = true;
				} else {
					value = "#name";
				}
			}
			writer.write(value);
			writer.write(" to ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			if (isLocal) {
				writer.write(" on :input");				
			} else if (value.equalsIgnoreCase("#name")) {
				writer.write(" on :speaker");				
			} else {
				writer.write(" on :conversation");
			}
		} else if (name.equals("map")) {
			// EXT - value lets the value be set for a map.
			String value = getNodeValue(child, "value", null, true, false, multiStar, srai, network);
			if (value == null) {
				writer.write("get ");
				String type = getNodeValue(child, "name", null, true, false, multiStar, srai, network);
				if (type == null) {
					type = "#meaning";
				}
				writer.write(type);
				writer.write(" from ");
			} else {
				writer.write("set ");
				String type = getNodeValue(child, "name", null, true, false, multiStar, srai, network);
				if (type == null) {
					type = "#meaning";
				}
				writer.write(type);
				writer.write(" to ");
				writer.write(value);
				writer.write(" on ");
			}
			appendNestedString((Element)child, multiStar, srai, writer, network);
		} else if (name.equals("new")) {
			writer.write("new ");
			String type = getNodeValue(child, "name", null, true, false, multiStar, srai, network);
			if (type == null) {
				type = "#thing";
			}
			writer.write(type);
		} else if (name.equals("think")) {
			writer.write("think (");
			appendThink((Element)child, writer, multiStar, srai, network);
			writer.write(")");
		} else if (name.equals("eval")) {
			writer.write("eval (");
			appendThink((Element)child, writer, multiStar, srai, network);
			writer.write(")");
		} else if (name.equals("learn")) {
			writer.write("learn ");
			List<Element> elements = getLocalElementsByTagName("category", (Element)child);
			if (elements != null && elements.size() > 0) {
				Element category = elements.get(0);
				elements = getLocalElementsByTagName("pattern", category);
				if (elements != null && elements.size() > 0) {
					Element pattern = elements.get(0);
					String text = getTemplate(pattern, false, multiStar, srai, false, false, true, network).toLowerCase();
					Vertex vertex = null;
					if (isPattern(text)) {
						vertex = network.createPattern(text);
						writer.write(vertex.printString());
					} else {
						writer.write("\"");
						writer.write(text.replace("\"", "\"\""));
						writer.write("\"");
					}
				}
				elements = getLocalElementsByTagName("that", category);
				if (elements != null && elements.size() > 0) {
					Element that = elements.get(0);
					String text = getPattern(that, network).toLowerCase();
					writer.write(" that ");
					Vertex vertex = null;
					if (isPattern(text)) {
						vertex = network.createPattern(text);
						writer.write(vertex.printString());
					} else {
						writer.write("\"");
						writer.write(text.replace("\"", "\"\""));
						writer.write("\"");
					}
				}
				elements = getLocalElementsByTagName("topic", category);
				if (elements != null && elements.size() > 0) {
					Element topic = elements.get(0);
					String text = getPattern(topic, network).toLowerCase();
					writer.write(" topic ");
					Vertex vertex = null;
					if (isPattern(text)) {
						vertex = network.createPattern(text);
						writer.write(vertex.printString());
					} else {
						writer.write("\"");
						writer.write(text.replace("\"", "\"\""));
						writer.write("\"");
					}
				}
				elements = getLocalElementsByTagName("template", category);
				if (elements != null && elements.size() > 0) {
					Element template = elements.get(0);
					writer.write(" template ");
					String text = getTemplate(template, false, multiStar, srai, false, false, false, network);
					if (text.startsWith("Formula:")) {
						writer.write(text);
					} else {
						writer.write("\"");
						writer.write(text);
						writer.write("\"");
					}
				}
			}
		} else if (name.equals("srai")) {
			writer.write("srai ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			srai[0] = true;
		} else if (name.equals("sraix")) {
			writer.write("sraix ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			String value = getNodeValue(child, "bot", null, false, true, multiStar, srai, network);
			if (value != null) {
				writer.write(" bot ");
				writer.write(value);
			} else {
				// Allow name element, as bot element is already a command.
				String botname = getNodeValue(child, "botname", null, false, true, multiStar, srai, network);
				if (botname != null) {
					writer.write(" bot ");
					writer.write(botname);
				}
			}
			value = getNodeValue(child, "botid", null, false, true, multiStar, srai, network);
			if (value != null) {
				writer.write(" botid ");
				writer.write(value);
			}
			value = getNodeValue(child, "service", null, true, false, multiStar, srai, network);
			if (value != null) {
				writer.write(" service ");
				writer.write(value);
			}
			value = getNodeValue(child, "server", null, false, true, multiStar, srai, network);
			if (value != null) {
				writer.write(" server ");
				writer.write(value);
			}
			value = getNodeValue(child, "apikey", null, false, true, multiStar, srai, network);
			if (value != null) {
				writer.write(" apikey ");
				writer.write(value);
			}
			value = getNodeValue(child, "limit", null, false, false, multiStar, srai, network);
			if (value != null) {
				writer.write(" limit ");
				writer.write(value);
			}
			value = getNodeValue(child, "hint", null, false, true, multiStar, srai, network);
			if (value != null) {
				writer.write(" hint ");
				writer.write(value);
			}
			value = getNodeValue(child, "default", null, false, true, multiStar, srai, network);
			if (value != null) {
				writer.write(" default ");
				writer.write(value);
			}
		} else if (name.equals("sr")) {
			writer.write("srai ");
			if (multiStar) {
				writer.write("(get #word from :star at 1)");
			} else {
				writer.write(":star");
			}
			srai[0] = true;
		} else if (name.equals("id")) {
			writer.write("get #name from :speaker");
		} else if (name.equals("size")) {
			writer.write("call #size on #Utils");
		} else if (name.equals("vocabulary")) {
			writer.write("call #size on #Utils");
		} else if (name.equals("program")) {
			writer.write("call #program on #Utils");
		//} else if (name.equals("system")) { -- os execution definitely not allowed
		} else if (name.equals("version")) {
			writer.write("call #version on #Utils");
		} else if (name.equals("uppercase")) {
			writer.write("uppercase ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
		} else if (name.equals("lowercase")) {
			writer.write("lowercase ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
		} else if (name.equals("formal")) {
			writer.write("format ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			writer.write(" as #formal");
		} else if (name.equals("sentence")) {
			writer.write("format ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			writer.write(" as #sentence");
		} else if (name.equals("explode")) {
			writer.write("format ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			writer.write(" as #explode");
		} else if (name.equals("normalize")) {
			writer.write("format ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			writer.write(" as #normalize");
		} else if (name.equals("denormalize")) {
			writer.write("format ");
			appendNestedString((Element)child, multiStar, srai, writer, network);
			writer.write(" as #denormalize");
		} else if (name.equals("gender")) {
			writer.write("format ");
			if (child.getChildNodes().getLength() == 0) {
				if (multiStar) {
					writer.write("(get #word from :star at 1)");					
				} else {
					writer.write(":star");
				}
			} else {
				appendNestedString((Element)child, multiStar, srai, writer, network);
			}
			writer.write(" as #gender");
		} else if (name.equals("person")) {
			writer.write("format ");
			if (child.getChildNodes().getLength() == 0) {
				if (multiStar) {
					writer.write("(get #word from :star at 1)");					
				} else {
					writer.write(":star");
				}
			} else {
				appendNestedString((Element)child, multiStar, srai, writer, network);
			}
			writer.write(" as #person");
		} else if (name.equals("person2")) {
			writer.write("format ");
			if (child.getChildNodes().getLength() == 0) {
				if (multiStar) {
					writer.write("(get #word from :star at 1)");					
				} else {
					writer.write(":star");
				}
			} else {
				appendNestedString((Element)child, multiStar, srai, writer, network);
			}
			writer.write(" as #person2");
		} else if (name.equals("random")) {
			writer.write("random (");
			List<Element> children = getLocalElementsByTagName("li", (Element)child);
			for (Iterator<Element> iterator = children.iterator(); iterator.hasNext(); ) {
				Element random = iterator.next();
				appendNestedString(random, multiStar, srai, writer, network);
				if (iterator.hasNext()) {
					writer.write(", ");
				}
			}
			writer.write(")");
		} else if (name.equals("loop")) {
			writer.write("think (assign :loop to #true)");
		} else if (name.equals("condition")) {
			NodeList loop = child.getElementsByTagName("loop");
			if (loop != null && loop.getLength() > 0) {
				writer.write("do (assign :result to \"\", assign :loop to #true, while (:loop) do (assign :loop to #false, ");	
				writer.write("assign :result to Formula:\"{:result}{");			
			}
			String conditionName = getNodeValue(child, "name", null, true, false, multiStar, srai, network);
			boolean isLocal = false;
			if (conditionName == null) {
				conditionName = getNodeValue(child, "var", null, true, false, multiStar, srai, network);
				if (conditionName != null) {
					isLocal = true;
				} else {
					conditionName = ":star";
				}
			}
			String conditionValue = getNodeValue(child, "value", null, false, true, multiStar, srai, network);
			if (conditionName != null && conditionValue != null) {
				writer.write("if (get ");
				writer.write(conditionName);
				if (isLocal) {
					writer.write(" from :input, ");				
				} else if (conditionName.equalsIgnoreCase("#name")) {
					writer.write(" from :speaker, ");				
				} else {
					writer.write(" from :conversation, ");
				}
				boolean isText = conditionValue.startsWith("\"");
				if (isText) {
					writer.write("Pattern:");
				}
				writer.write(conditionValue);
				writer.write(") then ");
				appendNestedString(child, multiStar, srai, writer, network);
				writer.write(" else \"\"");
			} else {
				List<Element> children = getLocalElementsByTagName("li", (Element)child);
				boolean catchAll = false;
				for (Iterator<Element> iterator = children.iterator(); iterator.hasNext(); ) {
					boolean liLocal = isLocal;
					Element condition = iterator.next();
					String liName = getNodeValue(condition, "name", null, true, false, multiStar, srai, network);
					if (liName == null) {
						liName = getNodeValue(condition, "var", null, true, false, multiStar, srai, network);
						if (liName != null) {
							liLocal = true;
						}
					} else {
						liLocal = false;
					}
					String liValue = getNodeValue(condition, "value", null, false, true, multiStar, srai, network);
					if (liName == null) {
						liName = conditionName;
					}
					if (liValue == null) {
						liValue = conditionValue;
					}
					if (liValue == null) {
						appendNestedString(condition, multiStar, srai, writer, network);
						catchAll = true;
						break;
					} else {
						writer.write("if (get ");
						writer.write(liName);
						if (liLocal) {
							writer.write(" from :input, ");				
						} else if (conditionName.equalsIgnoreCase("#name")) {
							writer.write(" from :speaker, ");				
						} else {
							writer.write(" from :conversation, ");
						}
						boolean isText = liValue.startsWith("\"");
						if (isText) {
							writer.write("Pattern:");
						}
						writer.write(liValue);
						writer.write(") then ");
						appendNestedString(condition, multiStar, srai, writer, network);
						writer.write(" else ");
					}
				}
				if (!catchAll) {
					writer.write("\"\"");				
				}
			}
			if (loop != null && loop.getLength() > 0) {
				writer.write("}\"))");				
			}
		} else if (name.equals("request") || name.equals("input")) {
			String index = getNodeValue(child, "index", "1", false, false, multiStar, srai, network);
			String part = null;
			if (index != null) {
				if (index.indexOf(',') != -1) {
					index = index.substring(0, index.indexOf(','));
					part = index.substring(index.indexOf(',') + 1, index.length());
				}
			}
			writer.write("input ");
			writer.write(index);
			if (part != null) {
				writer.write(" part ");
				writer.write(part);
			}
			writer.write(" for :speaker");
		} else if (name.equals("that") || name.equals("response")) {
			String index = getNodeValue(child, "index", "1", false, false, multiStar, srai, network);
			String part = null;
			if (index != null) {
				if (index.indexOf(',') != -1) {
					index = index.substring(0, index.indexOf(','));
					part = index.substring(index.indexOf(',') + 1, index.length());
				}
			}
			writer.write("input ");
			writer.write(index);
			if (part != null) {
				writer.write(" part ");
				writer.write(part);
			}
			writer.write(" for :target");
		} else if (name.equals("date")) {
			String format = getNodeValue(child, "format", null, false, true, multiStar, srai, network);
			String jformat = getNodeValue(child, "jformat", null, false, true, multiStar, srai, network);
			if (jformat != null) {
				writer.write("call #dateWithFormat on #Watch with " + jformat);
			} else if (format != null) {
				writer.write("call #date on #Watch with " + format);					
			} else {
				writer.write("call #date on #Watch");
			}
		} else if (name.equals("interval")) {
			String format = getNodeValue(child, "jformat", null, false, true, multiStar, srai, network);
			String from = getNodeValue(child, "from", null, false, true, multiStar, srai, network);
			String to = getNodeValue(child, "to", null, false, true, multiStar, srai, network);
			String style = getNodeValue(child, "style", null, false, true, multiStar, srai, network);
			if (format != null) {
				writer.write("call #interval on #Watch with (" + style + ", " + from + ", " + to + ", " + format + ")");
			} else {
				writer.write("call #interval on #Watch with (" + style + ", " + from + ", " + to + ")");				
			}
		} else if (name.equals("star")) {
			String index = getNodeValue(child, "index", null, false, false, multiStar, srai, network);
			if (index == null) {
				if (multiStar) {
					writer.write("get #word from :star at 1");					
				} else {
					writer.write(":star");
				}
			} else {
				writer.write("get #word from :star at ");
				writer.write(index);
			}
		} else if (name.equals("thatstar")) {
			String index = getNodeValue(child, "index", null, false, false, multiStar, srai, network);
			if (index == null) {
				writer.write(":thatstar");
			} else {
				writer.write("get #word from :thatstar at ");
				writer.write(index);
			}
		} else if (name.equals("topicstar")) {
			String index = getNodeValue(child, "index", null, false, false, multiStar, srai, network);
			if (index == null) {
				writer.write(":topicstar");
			} else {
				writer.write("get #word from :topicstar at ");
				writer.write(index);
			}
		} else {
			writer.write("think (#" + name + ")");			
		}
	}
	public void appendPatternCode(Element child, boolean multiStar, boolean[] srai, StringWriter writer, Network network) {
		String name = child.getNodeName().toLowerCase();
		if (name.equals("bot")) {
			writer.write("get ");
			writer.write(getNodeValue(child, "name", "#name", true, false, multiStar, srai, network));
			writer.write(" from :target");
		} else if (name.equals("get")) {			
			writer.write("get ");
			String value = getNodeValue(child, "name", null, true, false, multiStar, srai, network);
			boolean isLocal = false;
			if (value == null) {
				value = getNodeValue(child, "var", null, true, false, multiStar, srai, network);
				if (value != null) {
					isLocal = true;
				} else {
					value = "#name";
				}
			}
			writer.write(value);
			if (isLocal) {
				writer.write(" from :input");				
			} else if (value.equalsIgnoreCase("#name")) {
				writer.write(" from :speaker");				
			} else {
				writer.write(" from :conversation");
			}
		} else if (name.equals("set")) {
			writer.write(":^");
			String value = child.getTextContent().trim();
			writer.write(value);
		}
	}
	
	public void appendThink(Element element, StringWriter writer, boolean multiStar, boolean[] srai, Network network) {
		NodeList list = element.getChildNodes();
		List<Element> elements = new ArrayList<Element>();
		for (int index = 0; index < list.getLength(); index++) {
			Node child = list.item(index);
			if (child instanceof Element) {
				elements.add((Element)child);
			}
		}
		if (elements.isEmpty()) {
			writer.write("#null");
		}
		if (elements.size() > 1) {
			writer.write("do (");
		}
		int index = 0;
		for (Element child : elements) {
			appendCode((Element)child, multiStar, srai, writer, network);
			index++;
			if (index < elements.size()) {
				writer.write(", ");
			}
		}
		if (elements.size() > 1) {
			writer.write(")");
		}
	}
	
	@Override
	public String toString() {
		return getClass().getSimpleName();
	}
}
