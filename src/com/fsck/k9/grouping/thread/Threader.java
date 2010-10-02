package com.fsck.k9.grouping.thread;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import android.util.Log;

import com.fsck.k9.K9;
import com.fsck.k9.grouping.MessageInfo;
import com.fsck.k9.helper.Utility;

/**
 * <a href="http://www.jwz.org/doc/threading.html">Jamie Zawinski's message
 * threading algorithm</a> implementation.
 */
public class Threader
{

    /**
     * @param <T>
     *            {@link MessageInfo} payload
     * @param <R>
     *            Result
     * @see Threader#walkIterative(ContainerWalk, Container)
     */
    public static interface ContainerWalk<T, R>
    {

        public static enum WalkAction
        {
            /**
             * Go on iterating
             */
            CONTINUE,
            /**
             * Halt iteration
             */
            HALT,
            /**
             * Restart iteration at last known node
             */
            LAST,
        }

        /**
         * Called once for the root node at start
         * 
         * @param root
         * @return {@link WalkAction#CONTINUE} to continue iteration,
         *         {@link WalkAction#HALT} to halt iteration. Any other value
         *         will cause an {@link IllegalStateException}.
         */
        WalkAction processRoot(Container<T> root);

        /**
         * Called for each root descendant until end is reached or
         * {@link WalkAction#HALT} is returned. If {@link WalkAction#LAST} is
         * returned for the first node (the root first child), this method will
         * be called again for the root node.
         * 
         * <p>
         * You can only rewind once in a row: additional rewind attempts will
         * stay on the same node.
         * </p>
         * 
         * <p>
         * Be careful not to go into infinite iteration (happens when always
         * returning {@link WalkAction#LAST} for the same node)!
         * </p>
         * 
         * @param node
         * @return {@link WalkAction#CONTINUE} to continue iteration,
         *         {@link WalkAction#HALT} to halt iteration,
         *         {@link WalkAction#LAST} to rewind iteration to the last
         *         processed node.
         */
        WalkAction processNode(Container<T> node);

        /**
         * Called once
         */
        void finish();

        R result();
    }

    /**
     * Helper method to iterate throught a tree of {@link Container}.
     * 
     * <p>
     * Call {@link ContainerWalk#processRoot(Container)} once for the root node,
     * then {@link ContainerWalk#processNode(Container)} for each root
     * descandant one and finally {@link ContainerWalk#finish()} at the end of
     * the iteration.
     * </p>
     * 
     * <p>
     * Tree <strong>must not</strong> be circular.
     * </p>
     * 
     * @param <T>
     * @param <R>
     * @param walk
     * @param root
     * @return Whatever the {@link ContainerWalk#result() walk} argument returns
     */
    public static <T, R> R walkIterative(final ContainerWalk<T, R> walk, final Container<T> root)
    {
        ContainerWalk.WalkAction action;

        action = walk.processRoot(root);

        switch (action)
        {
            case LAST:
                throw new IllegalStateException(
                        "Only CONTINUE/HALT are supported for the root node");
            case HALT:
                walk.finish();
                return walk.result();
            case CONTINUE:
                break;
            default:
                throw new IllegalStateException("Unkown action: " + action);
        }

        Container<T> last = root;

        main: for (Container<T> current = root.child; current != null;)
        {
            action = walk.processNode(current);

            choose: switch (action)
            {
                case HALT:
                    break main;
                case LAST:
                    if (current == root)
                    {
                        throw new IllegalStateException(
                                "Only CONTINUE/HALT are supported for the root node");
                    }
                    else if (last.parent == null)
                    {
                        // last has no parent, meaning that it has been removed
                        // from the tree, restarting iteration
                        current = root;
                    }
                    else
                    {
                        current = last;
                    }
                    continue main;
                case CONTINUE:
                    break choose;
                default:
                    throw new IllegalStateException("Unkown action: " + action);
            }

            last = current;

            if (current.child != null)
            {
                // there is a child, going deeper
                current = current.child;
            }
            else if (current != root && current.next != null)
            {
                // no child but siblings
                current = current.next;
            }
            else if (current != root && current.parent != null)
            {
                // we're the last descendant in this path, we have to find the
                // nearest "next" by going up
                do
                {
                    if (consistencyCheck && current.parent == null)
                    {
                        // something is wrong with this tree: can't find our parent back!
                        throw new IllegalStateException(
                                "Tree is inconsistent: unable to track back parent node for "
                                        + current);
                    }

                    // back to parent
                    current = current.parent;

                    if (current == root)
                    {
                        break main;
                    }
                }
                while (current.next == null);

                // go to siblings!
                current = current.next;
            }
            else
            {
                current = null;
            }
        }

        walk.finish();

        return walk.result();
    }

    private static final boolean consistencyCheck = true;

    private static final String EMPTY_SUBJECT = "";

    /**
     * @param <T>
     * @param messages
     *            Never <code>null</code>.
     * @param compactTree
     *            Whether the resulting tree should be compacted by pruning
     *            empty containers so that obvious empty containers are removed
     *            and their children re-parented.
     * @return A virtual root node. Never <code>null</code>.
     */
    public <T> Container<T> thread(final Collection<MessageInfo<T>> messages,
            final boolean compactTree)
    {
        if (messages.isEmpty())
        {
            return new Container<T>();
        }

        final boolean devDebug = K9.DEBUG && Log.isLoggable(K9.LOG_TAG, Log.VERBOSE);

        // 1. Index messages
        Map<String, Container<T>> containers = indexMessages(messages);

        // 2. Find the root set.
        final Container<T> firstRoot = findRoot(containers);

        if (devDebug)
        {
            Log.v(K9.LOG_TAG,
                    MessageFormat
                            .format("Threader: initial={0} | index w/-empty={1} w/o-empty={2} | root set: w/-empty={3} w/o-empty={4}",
                                    messages.size(), Threader.count(containers, true),
                                    Threader.count(containers, false),
                                    Threader.count(firstRoot, 0, true),
                                    Threader.count(firstRoot, 0, false)));
        }

        // 3. Discard id_table. We don't need it any more.
        containers = null;

        final Container<T> fakeRoot = new Container<T>();
        addChild(fakeRoot, firstRoot);

        // 4. Prune empty containers.
        if (compactTree)
        {
            try
            {
                //                pruneEmptyContainer(null, fakeRoot, fakeRoot);
                pruneEmptyContainer(fakeRoot);
            }
            catch (final StackOverflowError e)
            {
                Log.w(K9.LOG_TAG, "Whoops! let's keep the tree untouched if possible", e);
            }
            if (devDebug)
            {
                Log.v(K9.LOG_TAG, MessageFormat.format(
                        "Threader: after prune: w/-empty={0} w/o-empty={1}",
                        Threader.count(fakeRoot, true), Threader.count(fakeRoot, false)));
            }
        }

        // 5. Group root set by subject.
        groupRootBySubject(fakeRoot);

        return fakeRoot;

    }

    /**
     * If any two members of the root set have the same subject, merge them.
     * This is so that messages which don't have References headers at all still
     * get threaded (to the extent possible, at least.)
     * 
     * @param <T>
     * @param fakeRoot
     *            Never <code>null</code>.
     */
    private <T> void groupRootBySubject(final Container<T> fakeRoot)
    {
        int lastCount = -1;
        // counting is a CPU-intensive operation as it involves tree walking!
        final boolean devDebug = K9.DEBUG && Log.isLoggable(K9.LOG_TAG, Log.VERBOSE);
        if (devDebug)
        {
            lastCount = count(fakeRoot, false);
        }

        // A. Construct a new hash table, subject_table, which associates
        // subject strings with Container objects.
        final Map<String, Container<T>> subjectTable = new HashMap<String, Container<T>>();

        // B. For each Container in the root set:
        for (Container<T> root = fakeRoot.child; root != null; root = root.next)
        {
            // Find the subject of that sub-tree:
            final String subject = extractSubject(root, true);

            if (subject.length() == 0)
            {
                // If the subject is now "", give up on this Container.
                continue;
            }

            // Add this Container to the subject_table if:
            final Container<T> otherRoot = subjectTable.get(subject);
            if (otherRoot == null)
            {
                // There is no container in the table with this subject, or
                subjectTable.put(subject, root);
            }
            else if (root.message == null && otherRoot.message != null)
            {
                // This one is an empty container and the old one is not: the
                // empty one is more interesting as a root, so put it in the
                // table instead.
                subjectTable.put(subject, root);
            }
            else if (extractSubject(otherRoot, false).length() > subject.length()
                    && subject.equals(extractSubject(root, false)))
            {
                // The container in the table has a ``Re:'' version of this
                // subject, and this container has a non-``Re:'' version of this
                // subject. The non-re version is the more interesting of the
                // two.
                subjectTable.put(subject, root);
            }
        }

        // C. Now the subject_table is populated with one entry for each subject
        // which occurs in the root set. Now iterate over the root set, and
        // gather together the difference.

        Container<T> next;
        // For each Container in the root set:
        for (Container<T> root = fakeRoot.child; root != null; root = next)
        {
            // XXX DEBUG VARIABLE
            String action = null;

            // saving next now since current root might be removed from
            // siblings!
            next = root.next;

            // Find the subject of this Container (as above.)
            final String subject = extractSubject(root, true);

            // Look up the Container of that subject in the table.
            final Container<T> otherRoot = subjectTable.get(subject);

            if (otherRoot == null || otherRoot == root)
            {
                // If it is null, or if it is this container, continue.
                continue;
            }

            if (next == otherRoot)
            {
                // making sure we don't compare them twice
                next = otherRoot.next;
            }

            // Otherwise, we want to group together this Container and the one
            // in the table. There are a few possibilities:

            final MessageInfo<T> thisMessage = root.message;
            final MessageInfo<T> thatMessage = otherRoot.message;
            final boolean thisEmpty = thisMessage == null;
            final boolean thatEmpty = thatMessage == null;
            if (thisEmpty && thatEmpty)
            {
                // If both are dummies, append one's children to the other, and
                // remove the now-empty container.
                final Container<T> otherChild = otherRoot.child;
                removeChild(otherChild, true);
                addChild(root, otherChild);
                removeChild(otherRoot);

                // we just removed the matching node from the tree, we have to
                // replace it in the index
                subjectTable.put(subject, root);

                if (devDebug)
                {
                    action = "both are dummies";
                }
            }
            else if (thisEmpty ^ thatEmpty)
            {
                // If one container is a empty and the other is not, make the
                // non-empty one be a child of the empty, and a sibling of the
                // other ``real'' messages with the same subject (the empty's
                // children.)
                if (thisEmpty)
                {
                    removeChild(otherRoot);
                    addChild(root, otherRoot);

                    // removed from tree, replacing
                    subjectTable.put(subject, root);
                }
                else
                {
                    removeChild(root);
                    addChild(otherRoot, root);
                }

                if (devDebug)
                {
                    action = "one container is a empty and the other is not";
                }
            }
            else
            {
                String tempMatcher;
                final boolean thatIsResponse = Utility.stripSubject(
                        tempMatcher = thatMessage.getSubject()).length() < tempMatcher.trim()
                        .length();
                final boolean thisIsResponse = Utility.stripSubject(
                        tempMatcher = thisMessage.getSubject()).length() < tempMatcher.trim()
                        .length();
                tempMatcher = null;
                if (!thatEmpty && !thatIsResponse && thisIsResponse)
                {
                    // If that container is a non-empty, and that message's
                    // subject does not begin with ``Re:'', but this message's
                    // subject does, then make this be a child of the other.
                    removeChild(root);
                    addChild(otherRoot, root);

                    if (devDebug)
                    {
                        action = "that container is a non-empty, and that message's subject does not begin with ``Re:'', but this message's subject does";
                    }
                }
                else if (!thatEmpty && thatIsResponse && !thisIsResponse)
                {
                    // If that container is a non-empty, and that message's
                    // subject begins with ``Re:'', but this message's subject
                    // does not, then make that be a child of this one -- they
                    // were misordered. (This happens somewhat implicitly, since
                    // if there are two messages, one with Re: and one without,
                    // the one without will be in the hash table, regardless of
                    // the order in which they were seen.)
                    removeChild(otherRoot);
                    addChild(root, otherRoot);

                    // removed from tree, replacing
                    subjectTable.put(subject, root);

                    if (devDebug)
                    {
                        action = "that container is a non-empty, and that message's subject begins with ``Re:'', but this message's subject does not";
                    }
                }
                else
                {
                    // Otherwise, make a new empty container and make both msgs
                    // be a child of it. This catches the both-are-replies and
                    // neither-are-replies cases, and makes them be siblings
                    // instead of asserting a hierarchical relationship which
                    // might not be true.
                    final Container<T> newParent = new Container<T>();
                    spliceChild(otherRoot, newParent);
                    addChild(newParent, otherRoot);
                    removeChild(root);
                    addChild(newParent, root);

                    // removed from tree, replacing
                    subjectTable.put(subject, newParent);
                    if (devDebug)
                    {
                        action = "Otherwise";
                    }
                }
            }

            if (devDebug)
            {
                // FIXME trying to identify message loss...

                // again: count involves tree walking, it will affect performance!
                final int count = count(fakeRoot, false);
                if (count < lastCount)
                {
                    Log.v(K9.LOG_TAG, "Threader: groupRootBySubject: (loop end) WRONG! lastCount="
                            + lastCount + " count=" + count + " node=" + root + " action=" + action);
                    lastCount = count;
                }
            }
        }

        if (devDebug)
        {
            Log.v(K9.LOG_TAG, "Threader: groupRootBySubject: (end) count=" + count(fakeRoot, false));
        }
    }

    public static <T> boolean isCircular(final Container<T> node)
    {
        if (node == null || node.next == null)
        {
            return false;
        }
        final IdentityHashMap<Container<T>, Boolean> index = new IdentityHashMap<Container<T>, Boolean>();
        for (Container<T> current = node; current != null; current = current.next)
        {
            if (index.containsKey(current))
            {
                return true;
            }
            index.put(current, Boolean.TRUE);
        }
        return false;
    }

    /**
     * @param <T>
     * @param container
     * @param strip
     *            TODO
     * @return
     * @throws PatternSyntaxException
     */
    private <T> String extractSubject(Container<T> container, final boolean strip)
            throws PatternSyntaxException
    {
        String subject;
        if (container.message != null)
        {
            // If there is a message in the Container, the subject is the
            // subject of that message.
            subject = container.message.getSubject();
        }
        else
        {
            // If there is no message in the Container, then the Container
            // will have at least one child Container, and that Container
            // will have a message. Use the subject of that message instead.

            subject = findChildSubject(container);

        }

        if (strip)
        {
            // Strip ``Re:'', ``RE:'', ``RE[5]:'', ``Re: Re[4]: Re:'' and so on.
            subject = Utility.stripSubject(subject);
        }

        return subject;
    }

    /**
     * Find a subject in the child hierarchy. If no subject is found, an empty
     * String is returned. Walking through (siblings then children) the child
     * hierarchy is done until a non-empty String is found or if the end of the
     * hierarchy is reached.
     * 
     * @param <T>
     * @param container
     *            Never <code>null</code>
     * @return Found subject, or an empty String if not found
     */
    private <T> String findChildSubject(final Container<T> container)
    {
        String subject = EMPTY_SUBJECT;
        // since we are reparenting empty containers, the first child does not
        // necessarly contain a message, looping

        // siblings first
        for (Container<T> child = container.child; child != null; child = child.next)
        {
            final MessageInfo<T> childMessage = child.message;
            if (childMessage != null)
            {
                subject = childMessage.getSubject();
                break;
            }
        }

        if (EMPTY_SUBJECT.equals(subject))
        {
            // if siblings unsuccessful, going deeper
            for (Container<T> child = container.child; child != null; child = child.next)
            {
                subject = findChildSubject(child);
                if (!EMPTY_SUBJECT.equals(subject))
                {
                    break;
                }
            }
        }
        return subject;
    }

    /**
     * Debug method, count the number of messages in the given Map.
     * 
     * @param <T>
     * @param <I>
     * @param containers
     * @param countEmpty
     * @return Count
     */
    public static <T, I> int count(final Map<I, Container<T>> containers, final boolean countEmpty)
    {
        if (containers.isEmpty())
        {
            return 0;
        }
        int i = 0;
        if (countEmpty)
        {
            i = containers.size();
        }
        else
        {
            for (final Container<T> container : containers.values())
            {
                if (container.message != null)
                {
                    i++;
                }
            }
        }
        return i;
    }

    /**
     * Debug method, count the number of messages in the given tree.
     * 
     * <p>
     * Recursive method, subject to {@link StackOverflowError}.
     * </p>
     * 
     * @param <T>
     * @param node
     * @param count
     * @param countEmpty
     *            If <code>true</code>, empty messages are included in the sum.
     * @return Number of nodes (current (1) + descendants + <tt>count</tt>)
     * @see #count(Container, boolean)
     */
    public static <T> int count(final Container<T> node, final int count, final boolean countEmpty)
    {
        if (node == null)
        {
            return 0;
        }
        int i = 0;
        if (countEmpty || node.message != null)
        {
            i = 1;
        }
        if (node.child != null)
        {
            i = count(node.child, i, countEmpty);
        }
        if (node.next != null)
        {
            i = count(node.next, i, countEmpty);
        }
        return count + i;
    }

    /**
     * Iterative version of {@link #count(Container, int, boolean)}
     * 
     * @param <T>
     * @param root
     * @param countEmpty
     * @return Count
     */
    public static <T> int count(final Container<T> root, final boolean countEmpty)
    {
        return walkIterative(new ContainerWalk<T, Integer>()
        {

            private int count;

            @Override
            public WalkAction processRoot(final Container<T> root)
            {
                count = 0;
                return WalkAction.CONTINUE;
            }

            @Override
            public WalkAction processNode(final Container<T> node)
            {
                if (countEmpty || node.message != null)
                {
                    count++;
                }
                return WalkAction.CONTINUE;
            }

            @Override
            public void finish()
            {
                // no-op
            }

            @Override
            public Integer result()
            {
                return count;
            }
        }, root);
    }

    /**
     * Index the given message list into a {@link Map}&lt;{@link String},
     * {@link Container}&lt;<tt>T</tt>&gt;&gt; where:
     * <ul>
     * <li>the key is a Message-ID</li>
     * <li>the value is a corresponding {@link Container}, possibly empty (no
     * message) in case of reference tracking</li>
     * </ul>
     * 
     * @param <T>
     * @param messages
     *            Messages to index. Never <code>null</code>.
     * @return Resulting {@link Map}. Never <code>null</code>.
     */
    private <T> Map<String, Container<T>> indexMessages(final Collection<MessageInfo<T>> messages)
    {
        // use a LinkedHashMap to be more respectul of the original message ordering
        final Map<String, Container<T>> containers = new LinkedHashMap<String, Container<T>>();

        for (final MessageInfo<T> message : messages)
        {
            if (K9.DEBUG)
            {
                Log.v(K9.LOG_TAG, "Threader: indexing " + message.getId() + " / References: " + message.getReferences());
            }

            // A. If id_table contains an empty Container for this ID:
            String id = message.getId();
            Container<T> container;
            if ((container = containers.get(id)) != null && container.message == null)
            {
                // Store this message in the Container's message slot.
                container.message = message;
            }
            else
            {
                if (container != null)
                {
                    // ID clash detected!
                    if (K9.DEBUG)
                    {
                        Log.d(K9.LOG_TAG, "Threader: Message-ID clash detected for " + id);
                    }
                    // making this a follower of the original message
                    final List<String> newReferences = new ArrayList<String>(
                            message.getReferences());
                    newReferences.add(id);
                    message.setReferences(newReferences);

                    // and replacing ID with a self-generated one (it should be unique)
                    id = Integer.toHexString(System.identityHashCode(message));
                }

                // Else:
                // Create a new Container object holding this message;
                // Index the Container by Message-ID in id_table.
                container = new Container<T>();
                container.message = message;
                containers.put(id, container);
            }

            // B. For each element in the message's References field:
            Container<T> previous = null;
            for (final String reference : message.getReferences())
            {
                Container<T> referenceContainer;
                // Find a Container object for the given Message-ID:
                // If there's one in id_table use that;
                if ((referenceContainer = containers.get(reference)) == null)
                {
                    // Otherwise, make (and index) one with a null Message.
                    referenceContainer = new Container<T>();
                    containers.put(reference, referenceContainer);
                }

                if (previous != null)
                {
                    // Link the References field's Containers together in the
                    // order implied by the References header.

                    // If they are already linked, don't change the existing
                    // links.

                    // Do not add a link if adding that link would introduce a
                    // loop: that is, before asserting A->B, search down the
                    // children of B to see if A is reachable, and also search
                    // down the children of A to see if B is reachable. If
                    // either is already reachable as a child of the other,
                    // don't add the link.

                    if (!(reachable(previous, referenceContainer) || reachable(referenceContainer,
                            previous)))
                    {
                        if (referenceContainer.parent != null)
                        {
                            removeChild(referenceContainer);
                        }
                        addChild(previous, referenceContainer);
                    }
                }

                previous = referenceContainer;
            }

            if (previous != null)
            {
                // C. Set the parent of this message to be the last element in
                // References.

                // Note that this message may have a parent already: this can
                // happen because we saw this ID in a References field, and
                // presumed a parent based on the other entries in that field.
                // Now that we have the actual message, we can be more
                // definitive, so throw away the old parent and use this new
                // one. Find this Container in the parent's children list, and
                // unlink it.
                if (container.parent != null)
                {
                    removeChild(container);
                }
                addChild(previous, container);
            }

        }
        return containers;
    }

    /**
     * Walk over the elements of <tt>containers</tt>, and gather a list of the
     * Container objects that have no parents.
     * 
     * @param <T>
     * @param containers
     * @return First found root having no parent, as a linked list.
     *         <code>null</code> if none found.
     */
    private <T> Container<T> findRoot(final Map<String, Container<T>> containers)
    {
        Container<T> firstRoot = null;
        Container<T> lastRoot = null;
        for (final Container<T> c : containers.values())
        {
            if (c.parent == null)
            {
                if (firstRoot == null)
                {
                    firstRoot = c;
                }
                if (lastRoot != null)
                {
                    lastRoot.next = c;
                }
                lastRoot = c;
            }
        }
        return firstRoot;
    }

    /**
     * Prune empty containers. Recursively walk all containers under the root
     * set.
     * 
     * <p>
     * This method is subject to {@link StackOverflowError} in case of deep
     * hierarchy (even more if there are many empty containers).
     * </p>
     * 
     * @param <T>
     * @param previous
     *            Previous sibling. If <code>null</code>, that means
     *            <tt>container</tt> is the first child
     * @param container
     *            The node to remove from its parent/sibling list and eventually
     *            replaced with its children
     * @param root
     *            Hierarchy root
     */
    private <T> void pruneEmptyContainer(final Container<T> previous, final Container<T> container,
            final Container<T> root)
    {
        boolean removed = false;
        boolean promoted = false;
        final Container<T> child = container.child;
        final Container<T> next = container.next;

        if (container != root && container.message == null)
        {
            if (child == null)
            {
                // A. If it is an empty container with no children, nuke it.

                removeChild(container);
                removed = true;
            }
            else if (child != null)
            {
                // B. If the Container has no Message, but does have children,
                // remove this container but promote its children to this level
                // (that is, splice them in to the current child list.)

                // Do not promote the children if doing so would promote them to
                // the root set -- unless there is only one child, in which
                // case, do.
                if ((container.parent != null && container.parent != root)
                        || child.next == null)
                {
                    // not a root node OR only 1 child

                    spliceChild(container, child);
                    removed = true;
                    promoted = true;
                }
            }
        }

        // going deeper
        if (child != null && !promoted)
        {
            pruneEmptyContainer(null, child, root);
        }

        // going next
        if (promoted)
        {
            pruneEmptyContainer(previous, child, root);
        }
        else if (next != null)
        {
            pruneEmptyContainer(removed ? previous : container, next, root);
        }
    }

    /**
     * Iterative version of
     * {@link #pruneEmptyContainer(Container, Container, Container)}
     * 
     * @param <T>
     * @param fakeRoot
     *            The node under which empty containers should be removed. Never
     *            <code>null</code>.
     */
    private <T> void pruneEmptyContainer(final Container<T> fakeRoot)
    {
        final ContainerWalk<T, Void> walk = new ContainerWalk<T, Void>()
        {
            private Container<T> root;

            @Override
            public WalkAction processRoot(final Container<T> root)
            {
                this.root = root;
                return WalkAction.CONTINUE;
            }

            /*
             * Return false if tree walking is compromised. Removing current
             * node from tree would fall in this situation. Returning false
             * would trigger a new iteration loop on the tree (see following
             * loop).
             */
            @Override
            public WalkAction processNode(final Container<T> node)
            {
                if (node == root)
                {
                    // since we use the ability to rewind iteration,
                    // we have to properly ignore the root node here
                    return WalkAction.CONTINUE;
                }

                if (node.message == null)
                {
                    final Container<T> child = node.child;
                    if (child == null)
                    {
                        // A. If it is an empty container with no children, nuke it.

                        // if current node removed, drop iteration and start it again
                        removeChild(node);
                        return WalkAction.LAST;
                    }
                    else if (node.parent != root || child.next == null)
                    {
                        // B. If the Container has no Message, but does have children,
                        // remove this container but promote its children to this level
                        // (that is, splice them in to the current child list.)

                        // Do not promote the children if doing so would promote them to
                        // the root set -- unless there is only one child, in which
                        // case, do.

                        removeChild(child, true);
                        spliceChild(node, child);
                        return WalkAction.LAST;
                    }
                }
                return WalkAction.CONTINUE;
            }

            @Override
            public void finish()
            {
                // no-op
            }

            @Override
            public Void result()
            {
                return null;
            }
        };

        walkIterative(walk, fakeRoot);
    }

    /**
     * Add a child to a parent node.
     * 
     * <p>
     * As a concistency measure, new node (and its following siblings) will get
     * their old parent detached (element will be removed from parent children
     * list) and reset to the new parent.
     * </p>
     * 
     * @param <T>
     * @param parent
     *            Parent. Never <code>null</code>.
     * @param child
     *            Child to add. Never <code>null</code>.
     */
    private <T> void addChild(final Container<T> parent, final Container<T> child)
    {
        final Map<Container<T>, Boolean> currentChildren;
        if (consistencyCheck)
        {
            currentChildren = new IdentityHashMap<Container<T>, Boolean>();
        }
        else
        {
            currentChildren = Collections.emptyMap();
        }

        Container<T> sibling;
        if ((sibling = parent.child) == null)
        {
            // no children
            parent.child = child;
        }
        else
        {
            // at least one child, advancing
            while (sibling.next != null)
            {
                if (consistencyCheck)
                {
                    currentChildren.put(sibling, Boolean.TRUE);
                }
                sibling = sibling.next;
            }
            // last child, adding new sibling
            sibling.next = child;
        }

        // update the parent for the current node and its siblings

        // cache already verified parents for performance purpose
        final Map<Container<T>, Boolean> alreadyVerified;

        if (consistencyCheck)
        {
            alreadyVerified = new IdentityHashMap<Container<T>, Boolean>();
            // don't verify new parent
            alreadyVerified.put(parent, Boolean.TRUE);
        }
        else
        {
            alreadyVerified = Collections.emptyMap();
        }

        for (Container<T> newSibling = child; newSibling != null; newSibling = newSibling.next)
        {
            if (consistencyCheck || child == newSibling)
            {
                // discard old parent (make sure each getParent() is consistent)
                final Container<T> oldParent = newSibling.parent;
                if (oldParent != null
                        && (consistencyCheck ? !alreadyVerified.containsKey(oldParent)
                                : oldParent != parent))
                {
                    Container<T> previousOldSibling = null;
                    for (Container<T> oldSibling = oldParent.child; oldSibling != null; oldSibling = oldSibling
                            .next)
                    {
                        if (newSibling == oldSibling)
                        {
                            if (previousOldSibling == null)
                            {
                                oldParent.child = null;
                            }
                            else
                            {
                                previousOldSibling.next = null;
                            }
                            if (!consistencyCheck)
                            {
                                // TODO: actually, should break even if consistency check is enabled
                                break;
                            }
                        }
                        previousOldSibling = oldSibling;
                    }
                    if (consistencyCheck)
                    {
                        alreadyVerified.put(oldParent, Boolean.TRUE);
                    }
                }
            }
            // old parent was verified, replacing
            newSibling.parent = parent;

            if (consistencyCheck)
            {
                currentChildren.put(newSibling, Boolean.TRUE);
                if (currentChildren.containsKey(newSibling.next))
                {
                    // circular reference detected!
                    if (K9.DEBUG && Log.isLoggable(K9.LOG_TAG, Log.WARN))
                    {
                        Log.w(K9.LOG_TAG, MessageFormat.format(
                                "Circular reference detected on {0} / parent: {1} / next: {2}",
                                newSibling, newSibling.parent, newSibling.next));
                    }
                    newSibling.next = null;
                    break;
                }
            }
        }
    }

    /**
     * Remove a single node. Cancel the {@link Container#getNext()} link of the
     * removed node.
     * 
     * <p>
     * As a consistency measure following siblings of the removed child will get
     * their {@link Container#setParent(Container) parent} updated.
     * </p>
     * 
     * @param <T>
     * @param child
     *            Child to remove from its parent. Never <code>null</code>
     */
    private <T> void removeChild(final Container<T> child)
    {
        removeChild(child, false);
    }

    /**
     * Remove a single node. Cancel the {@link Container#getNext()} link of the
     * removed node.
     * 
     * <p>
     * As a consistency measure following siblings of the removed child will get
     * their {@link Container#setParent(Container) parent} updated.
     * </p>
     * 
     * @param <T>
     * @param child
     *            Child to remove from its parent. Never <code>null</code>
     * @param withSiblings
     *            TODO
     */
    private <T> void removeChild(final Container<T> child, final boolean withSiblings)
    {
        final Container<T> parent = child.parent;
        child.parent = null;
        if (parent.child == null)
        {
            return;
        }
        boolean found = false;
        Container<T> previous = null;
        for (Container<T> sibling = parent.child; sibling != null; sibling = sibling.next)
        {
            if (sibling == child)
            {
                found = true;
                if (withSiblings)
                {
                    if (previous == null)
                    {
                        parent.child = null;
                    }
                    else
                    {
                        previous.next = null;
                    }
                }
                else
                {
                    if (previous == null)
                    {
                        parent.child = sibling.next;
                    }
                    else
                    {
                        previous.next = sibling.next;
                    }
                    child.next = null;
                    break;
                }
            }
            else if (found && withSiblings)
            {
                sibling.parent = null;
            }

            previous = sibling;
        }
    }

    /**
     * Replace <tt>oldChild</tt> with <tt>newChild</tt> (and its current
     * {@link Container#getNext() siblings}) in the current (<tt>odlChild</tt> 
     * 's) sibling list.
     * 
     * <p>
     * <tt>newChild</tt> and its siblings will get their
     * {@link Container#setParent(Container) parent} updated.
     * </p>
     * 
     * @param <T>
     * @param oldChild
     *            Node to remove. Never <code>null</code>.
     * @param newChild
     *            Node to insert. Never <code>null</code>.
     */
    private <T> void spliceChild(final Container<T> oldChild, final Container<T> newChild)
    {
        final Container<T> parent = oldChild.parent;

        //        final Container<T> oldNext = oldChild.next;
        //        removeChild(newChild, true);
        //        removeChild(oldChild, true);
        //        oldChild.next = null;
        //        addChild(parent, newChild);
        //        if (oldNext != null)
        //        {
        //            addChild(parent, oldNext);
        //        }

        Container<T> previous = null;
        boolean found = false;
        for (Container<T> sibling = parent.child; sibling != null; sibling = sibling.next)
        {
            if (!found && sibling == oldChild)
            {
                if (previous == null)
                {
                    parent.child = newChild;
                }
                else
                {
                    previous.next = newChild;
                }
                sibling = newChild;
                found = true;
            }
            if (found)
            {
                sibling.parent = parent;
                if (sibling.next == null)
                {
                    sibling.next = oldChild.next;
                    break;
                }
            }
            previous = sibling;
        }
        if (found)
        {
            oldChild.next = null;
            oldChild.parent = null;
        }
    }

    /**
     * @param <T>
     * @param a
     *            Never <code>null</code>
     * @param b
     *            Never <code>null</code>
     * @return <code>true</code> if <tt>a</tt> is reachable as a descendant of
     *         <tt>b</tt> (or if they are the same), <code>false</code>
     *         otherwise
     */
    private <T> boolean reachable(Container<T> a, Container<T> b)
    {
        if (a == b)
        {
            return true;
        }
        for (Container<T> child = b.child; child != null; child = child.next)
        {
            if (child == a)
            {
                return true;
            }
            if (child.child != null && reachable(a, child.child))
            {
                return true;
            }
        }
        return false;
    }

}
