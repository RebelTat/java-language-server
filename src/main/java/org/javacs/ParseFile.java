package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public class ParseFile {
    private final JavaCompilerService parent;
    private final URI file;
    private final String contents;
    private final JavacTask task;
    private final Trees trees;
    private final CompilationUnitTree root;

    ParseFile(JavaCompilerService parent, URI file, String contents) {
        this.parent = parent;
        this.file = file;
        this.contents = contents;
        this.task = CompileFocus.singleFileTask(parent, file, contents);
        this.trees = Trees.instance(task);
        var profiler = new Profiler();
        task.addTaskListener(profiler);
        try {
            var it = task.parse().iterator();
            this.root = it.hasNext() ? it.next() : null; // TODO something better than null when no class is present
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
        
    }

    public boolean isTestMethod(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        for (var ann : method.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Test") || name.contentEquals("org.junit.Test")) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isTestClass(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof ClassTree)) return false;
        var cls = (ClassTree) leaf;
        for (var m : cls.getMembers()) {
            if (isTestMethod(new TreePath(path, m)))
                return true;
        }
        return false;
    }

    public List<TreePath> declarations() {
        var found = new ArrayList<TreePath>();
        class FindDeclarations extends TreePathScanner<Void, Void> {
            @Override 
            public Void visitClass​(ClassTree node, Void __) {
                var path = getCurrentPath();
                found.add(path);
                for (var m : node.getMembers()) {
                    found.add(new TreePath(path, m));
                }
                return super.visitClass(node, null);
            }
        }
        new FindDeclarations().scan(root, null);
        
        return found;
    }
    
    public Optional<TreePath> find(Ptr id) {
        class FindPosition extends TreePathScanner<Void, Void> {
            TreePath found;
            String className, memberName;

            String memberName(Tree m) {
                if (m instanceof MethodTree) {
                    var method = (MethodTree) m;
                    return method.getName().toString();
                } else if (m instanceof VariableTree) {
                    var field = (VariableTree) m;
                    return field.getName().toString();
                } else {
                    return "";
                }
            }

            @Override
            public Void visitClass(ClassTree c, Void nothing) {
                // Check if id references this class
                var path = getCurrentPath();
                if (new Ptr(path).equals(id)) {
                    found = path;
                    className = c.getSimpleName().toString();
                    memberName = null;
                }
                // Check if id references each method of this class
                for (var m : c.getMembers()) {
                    var child = new TreePath(path, m);
                    if (new Ptr(child).equals(id)) {
                        found = child;
                        className = c.getSimpleName().toString();
                        memberName = memberName(m);
                    }
                }
                // TODO this could be optimized by testing if this class is a prefix of id
                return super.visitClass(c, nothing);
            }
        }
        var finder = new FindPosition();
        finder.scan(root, null);
        return Optional.ofNullable(finder.found);
    }

    public Optional<Range> range(TreePath path) {
        return range(task, contents, path);
    }

    // TODO maybe this should return TreePath?
    public Optional<CompletionContext> completionPosition(int line, int character) {
        LOG.info(String.format("Finding completion position near %s(%d,%d)...", file, line, character));
        var pos = trees.getSourcePositions();
        var lines = root.getLineMap();
        var cursor = lines.getPosition(line, character);
        
        class FindCompletionPosition extends TreeScanner<Void, Void> {
            CompletionContext result = null;
            int insideClass = 0, insideMethod = 0;

            boolean containsCursor(Tree node) {
                return pos.getStartPosition(root, node) <= cursor && cursor <= pos.getEndPosition(root, node);
            }

            @Override
            public Void visitClass(ClassTree node, Void nothing) {
                insideClass++;
                super.visitClass(node, null);
                insideClass--;
                return null;
            }

            @Override
            public Void visitMethod(MethodTree node, Void nothing) {
                insideMethod++;
                super.visitMethod(node, null);
                insideMethod--;
                return null;
            }
            
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void nothing) {
                super.visitMemberSelect(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getExpression()) && result == null) {
                    LOG.info("...position cursor before '.' in " + node);
                    long offset = pos.getEndPosition(root, node.getExpression());
                    int line = (int) lines.getLineNumber(offset),
                            character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getIdentifier(), "");
                    result = new CompletionContext(line, character, insideClass > 0, insideMethod > 0, CompletionContext.Kind.MemberSelect, partialName);
                }
                return null;
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void nothing) {
                super.visitMemberReference(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getQualifierExpression()) && result == null) {
                    LOG.info("...position cursor before '::' in " + node);
                    long offset = pos.getEndPosition(root, node.getQualifierExpression());
                    int line = (int) lines.getLineNumber(offset),
                            character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getName(), "");
                    result = new CompletionContext(line, character, insideClass > 0, insideMethod > 0, CompletionContext.Kind.MemberReference, partialName);
                }
                return null;
            }

            @Override
            public Void visitCase(CaseTree node, Void nothing) {
                var containsCursor = containsCursor(node);
                for (var s : node.getStatements()) {
                    if (containsCursor(s))
                        containsCursor = false;
                }

                if (containsCursor) {
                    LOG.info("...position cursor after case " + node.getExpression());
                    long offset = pos.getEndPosition(root, node.getExpression());
                    int line = (int) lines.getLineNumber(offset),
                            character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getExpression(), "");
                    result = new CompletionContext(line, character, insideClass > 0, insideMethod > 0, CompletionContext.Kind.Case, partialName);
                } else {
                    super.visitCase(node, nothing);
                }
                return null;
            }
            
            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                super.visitIdentifier(node, nothing);

                if (containsCursor(node) && result == null) {
                    LOG.info("...position cursor after identifier " + node.getName());
                    var partialName = Objects.toString(node.getName(), "");
                    result = new CompletionContext(line, character, insideClass > 0, insideMethod > 0, CompletionContext.Kind.Identifier, partialName);
                }
                return null;
            }

            @Override
            public Void visitAnnotation(AnnotationTree node, Void nothing) {
                if (containsCursor(node.getAnnotationType()) && result == null) {
                    LOG.info("...position cursor after annotation " + node.getAnnotationType());
                    var id = (IdentifierTree) node.getAnnotationType();
                    var partialName = Objects.toString(id.getName(), "");
                    result = new CompletionContext(line, character, insideClass > 0, insideMethod > 0, CompletionContext.Kind.Annotation, partialName);
                } else {
                    super.visitAnnotation(node, nothing);
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                for (var t : node.getErrorTrees()) {
                    t.accept(this, null);
                }
                return null;
            }
        }
        var find = new FindCompletionPosition();
        find.scan(root, null);
        if (find.result == null) {
            LOG.info("...found nothing near cursor!");
            return Optional.empty();
        }
        return Optional.of(find.result);
    }

    static Optional<Range> range(JavacTask task, String contents, TreePath path) {
        // Find start position
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var root = path.getCompilationUnit();
        var lines = root.getLineMap();
        var start = (int) pos.getStartPosition(root, path.getLeaf());
        var end = (int) pos.getEndPosition(root, path.getLeaf());
        
        // If end is bad, guess based on start
        if (end == -1) {
            end = start + path.getLeaf().toString().length();
        }
        
        // If this is a class or method declaration, we need to refine the range
        if (path.getLeaf() instanceof ClassTree || path.getLeaf() instanceof MethodTree || path.getLeaf() instanceof VariableTree) {
            // Figure out what name to search for
            var className = JavaCompilerService.className(path);
            var memberName = JavaCompilerService.memberName(path);
            String searchFor;
            if (!memberName.isPresent()) searchFor = className;
            else if (memberName.get().equals("<init>")) searchFor = className;
            else searchFor = memberName.get();
            
            // Search text for searchFor
            start = contents.indexOf(searchFor, start);
            end = start + searchFor.length();
            if (start == -1)
                throw new RuntimeException(String.format("Couldn't find identifier `%s` in `%s`", searchFor, path.getLeaf()));
        }
        var startLine = (int) lines.getLineNumber(start);
        var startCol = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endCol = (int) lines.getColumnNumber(end);
        var range = new Range(new Position(startLine-1, startCol-1), new Position(endLine-1, endCol-1));

        return Optional.of(range);
    }

    private static final Logger LOG = Logger.getLogger("main");
}