/**
 * Copyright (c) 2015 DataTorrent, Inc.
 * All rights reserved.
 */
package com.datatorrent.stram.webapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.Component;
import com.datatorrent.api.Operator;
import com.datatorrent.stram.webapp.asm.ClassNodeType;
import com.datatorrent.stram.webapp.asm.CompactClassNode;
import com.datatorrent.stram.webapp.asm.CompactMethodNode;
import com.datatorrent.stram.webapp.asm.CompactUtil;
import com.datatorrent.stram.webapp.asm.MethodSignatureVisitor;
import com.datatorrent.stram.webapp.asm.Type;
import com.datatorrent.stram.webapp.asm.Type.ArrayTypeNode;
import com.datatorrent.stram.webapp.asm.Type.ParameterizedTypeNode;
import com.datatorrent.stram.webapp.asm.Type.TypeNode;
import com.datatorrent.stram.webapp.asm.Type.TypeVariableNode;
import com.datatorrent.stram.webapp.asm.Type.WildcardTypeNode;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * A graph data structure holds all type information and their relationship needed in app builder
 * ASM is used to retrieve fields, methods information and kept in java beans and then stored in this graph data structure
 * ASM is used to avoid memory leak and save permgen memory space
 * @since 2.1
 */
public class TypeGraph
{
  // classes to exclude when fetching getter/setter method or in other parsers
  public static String[] EXCLUDE_CLASSES = {Object.class.getName().replace('.', '/'), 
    Enum.class.getName().replace('.', '/'), 
    Operator.class.getName().replace('.', '/'),
    Component.class.getName().replace('.', '/')};

  enum UI_TYPE {

    LIST(Collection.class.getName(), "List"),

    ENUM(Enum.class.getName(), "Enum"),

    MAP(Map.class.getName(), "Map");

    private final String assignableTo;
    private final String name;

    private UI_TYPE(String assignableTo, String name)
    {
      this.assignableTo = assignableTo;
      this.name = name;
    }

    public static UI_TYPE getEnumFor(String clazzName, Map<String, TypeGraphVertex> typeGraph)
    {
      TypeGraphVertex tgv = typeGraph.get(clazzName);
      if (tgv == null) {
        return null;
      }
      for (UI_TYPE type : UI_TYPE.values()) {
        TypeGraphVertex typeTgv = typeGraph.get(type.assignableTo);
        if (typeTgv == null) {
          continue;
        }
        if (isAncestor(typeTgv, tgv)) {
          return type;
        }
      }
      return null;
    }

    private static boolean isAncestor(TypeGraphVertex typeTgv, TypeGraphVertex tgv)
    {
      if (tgv == typeTgv) {
        return true;
      }
      if ((tgv.ancestors == null || tgv.ancestors.size() == 0)) {
        return false;
      }
      for (TypeGraphVertex vertex : tgv.ancestors) {
        if (isAncestor(typeTgv, vertex))
          return true;
      }
      return false;
    }

    public String getName()
    {
      return name;
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(TypeGraph.class);

  private final Map<String, TypeGraphVertex> typeGraph = new HashMap<String, TypeGraphVertex>();

  private void addNode(InputStream input, String resName) throws IOException
  {
    try {
      
      ClassReader reader = new ClassReader(input);
      ClassNode classN = new ClassNodeType();
      reader.accept(classN, ClassReader.SKIP_CODE);
      CompactClassNode ccn = CompactUtil.compactClassNode(classN);
      String typeName = classN.name.replace('/', '.');

//      LOG.debug("Add type {} to the graph", typeName);
      
      TypeGraphVertex tgv = null;
      TypeGraphVertex ptgv = null;
      if (typeGraph.containsKey(typeName)) {
        tgv = typeGraph.get(typeName);
        tgv.setClassNode(ccn);
      } else {
        tgv = new TypeGraphVertex(typeName, resName, ccn);
        typeGraph.put(typeName, tgv);
      }
      String immediateP = reader.getSuperName();
      if (immediateP != null) {
        immediateP = immediateP.replace('/', '.');
        ptgv = typeGraph.get(immediateP);
        if (ptgv == null) {
          ptgv = new TypeGraphVertex(immediateP, resName);
          typeGraph.put(immediateP, ptgv);
        }
        tgv.ancestors.add(ptgv);
        ptgv.descendants.add(tgv);
      }
      if (reader.getInterfaces() != null) {
        for (String iface : reader.getInterfaces()) {
          iface = iface.replace('/', '.');
          ptgv = typeGraph.get(iface);
          if (ptgv == null) {
            ptgv = new TypeGraphVertex(iface, resName);
            typeGraph.put(iface, ptgv);
          }
          tgv.ancestors.add(ptgv);
          ptgv.descendants.add(tgv);
        }
      }

      updateInitializableDescendants(tgv);
    } finally {
      if (input != null) {
        input.close();
      }
    }
  }

  public void addNode(File file) throws IOException
  {
    addNode(new FileInputStream(file), file.getAbsolutePath());
  }

  public void addNode(JarEntry jarEntry, JarFile jar) throws IOException
  {
    addNode(jar.getInputStream(jarEntry), jar.getName());
  }

  private void updateInitializableDescendants(TypeGraphVertex tgv)
  {
    if(tgv.isInitializable()){
      tgv.allInitialiazableDescendants.add(tgv);
    }
    for (TypeGraphVertex parent : tgv.ancestors) {
      updateInitializableDescendants(parent, tgv.allInitialiazableDescendants);
    }
  }

  private void updateInitializableDescendants(TypeGraphVertex tgv, Set<TypeGraphVertex> allChildren)
  {

    tgv.allInitialiazableDescendants.addAll(allChildren);


    for (TypeGraphVertex parent : tgv.ancestors) {
      updateInitializableDescendants(parent, allChildren);
    }
  }

  public int size()
  {
    return typeGraph.size();
  }

  public Set<String> getDescendants(String fullClassName)
  {
    Set<String> result = new HashSet<String>();
    TypeGraphVertex tgv = typeGraph.get(fullClassName);
    if (tgv != null) {
      tranverse(tgv, false, result, Integer.MAX_VALUE);
    }
    return result;
  }

  public Set<String> getInitializableDescendants(String fullClassName, int limit)
  {
    return getInitializableDescendants(fullClassName, limit, null, null);
  }

  private void tranverse(TypeGraphVertex tgv, boolean onlyInitializable, Set<String> result, int limit)
  {
    if (!onlyInitializable) {
      result.add(tgv.typeName);
    }

    if (onlyInitializable && tgv.numberOfInitializableDescendants() > limit) {
      throw new RuntimeException("Too many public concrete sub types!");
    }

    if (onlyInitializable && tgv.isInitializable()) {
      result.add(tgv.typeName);
    }

    if (tgv.descendants.size() > 0) {
      for (TypeGraphVertex child : tgv.descendants) {
        tranverse(child, onlyInitializable, result, limit);
      }
    }
  }

  public static class TypeGraphVertex
  {

    /**
     * Vertex is unique by name, hashCode and equal depends only on typeName
     */
    public final String typeName;

    private CompactClassNode classNode = null;

    /**
     * All initializable(public type with a public non-arg constructor) implementations including direct and indirect descendants
     */
    private final transient Set<TypeGraphVertex> allInitialiazableDescendants = new HashSet<TypeGraphVertex>();

    private final transient Set<TypeGraphVertex> ancestors = new HashSet<TypeGraphVertex>();

    private final transient Set<TypeGraphVertex> descendants = new HashSet<TypeGraphVertex>();

    // keep the jar file name for late fetching the detail information
    private final String jarName;
    
    @SuppressWarnings("unused")
    private TypeGraphVertex(){
      jarName = "";
      typeName = "";
    }

    public TypeGraphVertex(String typeName, String jarName, CompactClassNode classNode)
    {

      this.jarName = jarName;
      this.typeName = typeName;
      this.classNode = classNode;
    }

    public int numberOfInitializableDescendants()
    {
      return allInitialiazableDescendants.size() + (isInitializable() ? 1 : 0);
    }

    public TypeGraphVertex(String typeName, String jarName)
    {
      this.typeName = typeName;
      this.jarName = jarName;
    }

    private boolean isInitializable()
    {
      return isPublicConcrete() && classNode.getInitializableConstructor() != null;
    }

    private boolean isPublicConcrete()
    {
      if (classNode == null) {
        // If the class is not in the classpath
        return false;
      }
      int opCode = getOpCode();

      // if the class is neither abstract nor interface
      // and the class is public
      return ((opCode & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0) && ((opCode & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC);
    }

    private int getOpCode()
    {
      List<CompactClassNode> icl = classNode.getInnerClasses();
      if (typeName.contains("$")) {
        for (CompactClassNode innerClassNode : icl) {
          if (innerClassNode.getName().replace('/', '.').equals(typeName)) {
            return innerClassNode.getAccess();
          }
        }
      }
      return classNode.getAccess();
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode()
    {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((typeName == null) ? 0 : typeName.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj)
    {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      TypeGraphVertex other = (TypeGraphVertex) obj;
      if (typeName == null) {
        if (other.typeName != null)
          return false;
      } else if (!typeName.equals(other.typeName))
        return false;
      return true;
    }

    public String getJarName()
    {
      return jarName;
    }


    public CompactClassNode getClassNode()
    {
      return classNode;
    }
    
    public void setClassNode(CompactClassNode classNode)
    {
      this.classNode = classNode;
    }
  }

  public Set<String> getInitializableDescendants(String clazz, int limit, String filter, String packagePrefix)
  {
    Set<String> result = new TreeSet<String>(new Comparator<String>() {

      @Override
      public int compare(String o1, String o2)
      {
        String n1 = o1;
        String n2 = o2;
        if(n1.startsWith("java")){
          n1 = "0" + n1;
        }
        if(n2.startsWith("java")){
          n2 = "0" + n2;
        }
        
        return n1.compareTo(n2);
      }
    });
    TypeGraphVertex tgv = typeGraph.get(clazz);

    if (tgv.numberOfInitializableDescendants() > limit) {
      throw new RuntimeException("Too many public concrete sub types!");
    }
    if (tgv != null) {
      for (TypeGraphVertex node : tgv.allInitialiazableDescendants) {
        if (filter != null && !Pattern.matches(filter, node.typeName)) {
          continue;
        }
        if (packagePrefix != null && !node.typeName.startsWith(packagePrefix)) {
          continue;
        }
        result.add(node.typeName);
      }
      if(tgv.isInitializable()){
        result.add(tgv.typeName);
      }
    }
    return result;
  }

  public JSONObject describeClass(String clazzName) throws JSONException
  {
    JSONObject desc = new JSONObject();
    desc.put("name", clazzName);
    TypeGraphVertex tgv = typeGraph.get(clazzName);
    if (tgv == null) {
      return desc;
    }
    CompactClassNode cn = tgv.classNode;
    if (cn.isEnum()) {

      List<String> enumNames = cn.getEnumValues();
      desc.put("enum", enumNames);
      desc.put("uiType", UI_TYPE.ENUM.getName());
    }
    UI_TYPE uType = UI_TYPE.getEnumFor(tgv.typeName, typeGraph);
    if (uType != null) {
      desc.put("uiType", uType.getName());
    }
    desc.put("properties", getClassProperties(clazzName));
    return desc;
  }

  private Collection<JSONObject> getClassProperties(String clazzName) throws JSONException
  {
    TypeGraphVertex tgv = typeGraph.get(clazzName);
    if (tgv == null) {
      return null;
    }
    Map<String, JSONObject> results = new TreeMap<String, JSONObject>();
    List<CompactMethodNode> getters =  new LinkedList<CompactMethodNode>();
    List<CompactMethodNode> setters = new LinkedList<CompactMethodNode>();
    getPublicSetterGetter(tgv, setters, getters);
    

    for (CompactMethodNode setter : setters) {
      String prop = WordUtils.uncapitalize(setter.getName().substring(3));
      JSONObject propJ = results.get(prop);
      if (propJ == null) {
        propJ = new JSONObject();
        propJ.put("name", prop);
        results.put(prop, propJ);
      }
      propJ.put("canSet", true);
      propJ.put("canGet", false);


      MethodSignatureVisitor msv = null;
      msv = setter.getMethodSignatureNode();
      if(msv==null){
        continue;
      }
      
      List<Type> param = msv.getParameters();
      if (CollectionUtils.isEmpty(param)) {
        propJ.put("type", "UNKNOWN");
      } else {
        // only one param in setter method
        setTypes(propJ, param.get(0));
        // propJ.put("type", param.getTypeObj().getClassName());

      }
      // propJ.put("type", typeString);
    }

    for (CompactMethodNode getter : getters) {
      int si = getter.getName().startsWith("is") ? 2 : 3;
      String prop = WordUtils.uncapitalize(getter.getName().substring(si));
      JSONObject propJ = results.get(prop);
      if (propJ == null) {
        propJ = new JSONObject();
        propJ.put("name", prop);
        results.put(prop, propJ);
        propJ.put("canSet", false);
        // propJ.put("type", Type.getReturnType(getter.desc).getClassName());

        MethodSignatureVisitor msv = null;
        msv = getter.getMethodSignatureNode();
        if(msv==null){
          continue;
        }
        

        Type rt = msv.getReturnType();
        if (rt == null) {
          propJ.put("type", "UNKNOWN");
        } else {
          setTypes(propJ, rt);
          // propJ.put("type", param.getTypeObj().getClassName());
        }

      }

      propJ.put("canGet", true);
    }

    return results.values();
  }

  private void getPublicSetterGetter(TypeGraphVertex tgv, List<CompactMethodNode> setters, List<CompactMethodNode> getters)
  {
    CompactClassNode exClass = null;
    // check if the class needs to be excluded
    for (String e : EXCLUDE_CLASSES) {
      if(e.equals(tgv.getClassNode().getName())) {
        exClass = tgv.getClassNode();
        break;
      }
    }
    if (exClass != null) {
      // if it's visiting classes need to be exclude from parsing, remove methods that override in sub class
      // So the setter/getter methods in Operater, Object, Class won't be counted
      for (CompactMethodNode compactMethodNode : exClass.getGetterMethods()) {
        for (Iterator<CompactMethodNode> iterator = getters.iterator(); iterator.hasNext();) {
          CompactMethodNode cmn = iterator.next();
          if (cmn.getName().equals(compactMethodNode.getName())) {
            iterator.remove();
          }
        }
      }
      for (CompactMethodNode compactMethodNode : exClass.getSetterMethods()) {
        for (Iterator<CompactMethodNode> iterator = setters.iterator(); iterator.hasNext();) {
          CompactMethodNode cmn = iterator.next();
          if (cmn.getName().equals(compactMethodNode.getName())) {
            iterator.remove();
          }
        }
      }
    } else {
      if (tgv.getClassNode().getSetterMethods() != null) {
        setters.addAll(tgv.getClassNode().getSetterMethods());
      }
      if (tgv.getClassNode().getGetterMethods() != null) {
        getters.addAll(tgv.getClassNode().getGetterMethods());
      }
    }
    for (TypeGraphVertex ancestor : tgv.ancestors) {
      getPublicSetterGetter(ancestor, setters, getters);
    }
  }

  private void setTypes(JSONObject propJ, Type t) throws JSONException
  {
    if (propJ == null) {
      return;
    } else {
      if (t instanceof WildcardTypeNode) {
        propJ.put("type", "?");
      } else if (t instanceof TypeNode) {
        TypeNode tn = (TypeNode) t;
        propJ.put("type", tn.getTypeObj().getClassName());
        UI_TYPE uiType = UI_TYPE.getEnumFor(tn.getTypeObj().getClassName(), typeGraph);
        if (uiType != null) {
          propJ.put("uiType", uiType.getName());
        }
        if (t instanceof ParameterizedTypeNode) {
          JSONArray jArray = new JSONArray();
          for (Type ttn : ((ParameterizedTypeNode) t).getActualTypeArguments()) {
            JSONObject objJ = new JSONObject();
            setTypes(objJ, ttn);
            jArray.put(objJ);
          }
          propJ.put("typeArgs", jArray);
        }
      }
      if (t instanceof WildcardTypeNode) {
        JSONObject typeBounds = new JSONObject();
        
        
        JSONArray jArray = new JSONArray();
        Type[] bounds = ((WildcardTypeNode) t).getUpperBounds();
        if(bounds!=null){
          for (Type type : bounds) {
            jArray.put(type.toString());
          }
        }
        typeBounds.put("upper", jArray);
        
        bounds = ((WildcardTypeNode) t).getLowerBounds();

        jArray = new JSONArray();
        if(bounds!=null){
          for (Type type : bounds) {
            jArray.put(type.toString());
          }
        }
        typeBounds.put("lower", jArray);

        propJ.put("typeBounds", typeBounds);

      }
      if(t instanceof ArrayTypeNode){
        propJ.put("type", t.getByteString());
        propJ.put("uiType", UI_TYPE.LIST.getName());
        
        JSONObject jObj = new JSONObject();
        setTypes(jObj, ((ArrayTypeNode)t).getActualArrayType());
        propJ.put("itemType", jObj);
      }
      
      if(t instanceof TypeVariableNode){
        propJ.put("typeLiteral", ((TypeVariableNode)t).getTypeLiteral());
        setTypes(propJ, ((TypeVariableNode)t).getRawTypeBound());
      }


    }
  }

  
  
  /**
   * Type graph is big bidirectional object graph which can not be serialized by kryo.
   * This class is alternative {@link TypeGraph} kryo serializer
   * The serializer rule is
   * #ofNodes + node array + relationship array(int array which the value is index of the node array)
   */
  public static class TypeGraphSerializer extends Serializer<TypeGraph>
  {

    @Override
    public void write(Kryo kryo, Output output, TypeGraph tg)
    {
      Map<String, Integer> indexes = new HashMap<String, Integer>();
      // write the size first
      kryo.writeObject(output, tg.typeGraph.size());
      int i = 0;
      // Sequentially write the vertexes
      for (Entry<String, TypeGraphVertex> e : tg.typeGraph.entrySet()) {
        indexes.put(e.getKey(), i++);
        kryo.writeObject(output, e.getValue());
      }
      
      // Sequentially store the descendants and initializable descendants relationships in index in vertex array
      for (Entry<String, TypeGraphVertex> e : tg.typeGraph.entrySet()) {
        int[] refs = fromSet(e.getValue().descendants, indexes);
        kryo.writeObject(output, refs);
        refs = fromSet(e.getValue().allInitialiazableDescendants, indexes);
        kryo.writeObject(output, refs);
      }
      
    }

    private int[] fromSet(Set<TypeGraphVertex> tgvSet, Map<String, Integer> indexes)
    {
      int[] result = new int[tgvSet.size()];
      int j = 0;
      for (TypeGraphVertex t : tgvSet) {
        result[j++] = indexes.get(t.typeName);
      }
      return result;
    }

    @Override
    public TypeGraph read(Kryo kryo, Input input, Class<TypeGraph> type)
    {
      // read the #vertex
      int vertexNo = kryo.readObject(input, Integer.class);
      // read the vertexes into array
      TypeGraphVertex[] tgv = new TypeGraphVertex[vertexNo];
      for (int i = 0; i < vertexNo; i++) {
        tgv[i] = kryo.readObject(input, TypeGraphVertex.class);
      }
      
      // build relations between vertexes
      for (int i = 0; i < tgv.length; i++) {
        int[] ref = kryo.readObject(input, int[].class);
        for (int j = 0; j < ref.length; j++) {
          tgv[i].descendants.add(tgv[ref[j]]);
          tgv[ref[j]].ancestors.add(tgv[i]);
        }
        
        ref = kryo.readObject(input, int[].class);
        for (int j = 0; j < ref.length; j++) {
          tgv[i].allInitialiazableDescendants.add(tgv[ref[j]]);
        }
        
      }
      TypeGraph result = new TypeGraph();
      for (TypeGraphVertex typeGraphVertex : tgv) {
        result.typeGraph.put(typeGraphVertex.typeName, typeGraphVertex);
      }
      return result;
    }

  }

}
