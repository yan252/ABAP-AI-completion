package com.sap.abap.ai.completion.editor;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * Manages the AI completion popup overlay on the editor.
 * Simulates Eclipse Content Assist by applying proposals to the document.
 */
public class AICompletionProposalPopup {

    private final ITextViewer viewer;
    private final IDocument document;

    public AICompletionProposalPopup(ITextViewer viewer) {
        this.viewer = viewer;
        this.document = viewer != null ? viewer.getDocument() : null;
    }

    /**
     * Shows a completion proposal in the Eclipse content assist popup.
     *
     * @param completionText the AI-generated completion text
     * @param offset         the cursor offset where to insert
     */
    public void showProposal(String completionText, int offset) {
        if (viewer == null || document == null || completionText == null || completionText.isEmpty()) {
            return;
        }

        List<ICompletionProposal> proposals = new ArrayList<>();
        proposals.add(new AICompletionProposal(completionText, offset));

        // Use Eclipse's content assist to show the popup
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
                new org.eclipse.jface.text.contentassist.ContentAssistant();
        // We apply the proposal directly - no need for the full ContentAssistant infrastructure

        // Apply the proposal directly to the document
        ICompletionProposal proposal = proposals.get(0);
        proposal.apply(document);
    }

    /**
     * Applies the completion text directly to the document at the cursor position.
     * This is used for "direct insert" mode.
     */
    public void applyDirect(String completionText, int offset) {
        if (document == null || completionText == null || completionText.isEmpty()) return;
        try {
            document.replace(offset, 0, completionText);
        } catch (BadLocationException e) {
            // ignore
        }
    }

    /**
     * A simple completion proposal that inserts the AI-generated text.
     */
    private static class AICompletionProposal implements ICompletionProposal, ICompletionProposalExtension2 {

        private final String completionText;
        private final int offset;

        AICompletionProposal(String completionText, int offset) {
            this.completionText = completionText;
            this.offset = offset;
        }

        @Override
        public void apply(IDocument doc) {
            try {
                doc.replace(offset, 0, completionText);
            } catch (BadLocationException e) {
                // ignore
            }
        }

        @Override
        public Point getSelection(IDocument doc) {
            return new Point(offset + completionText.length(), 0);
        }

        @Override
        public String getAdditionalProposalInfo() {
            return completionText;
        }

        @Override
        public String getDisplayString() {
            // Show first line as display text
            String firstLine = completionText.split("\n", 2)[0];
            if (firstLine.length() > 60) {
                firstLine = firstLine.substring(0, 57) + "...";
            }
            return "AI: " + firstLine;
        }

        @Override
        public org.eclipse.swt.graphics.Image getImage() {
            return null;
        }

        @Override
        public IContextInformation getContextInformation() {
            return null;
        }

        // ICompletionProposalExtension2 methods

        @Override
        public void apply(ITextViewer tv, char trigger, int stateMask, int docOffset) {
            IDocument doc = tv.getDocument();
            if (doc != null) {
                try {
                    doc.replace(docOffset, 0, completionText);
                } catch (BadLocationException e) {
                    // ignore
                }
            }
        }

        @Override
        public void selected(ITextViewer viewer, boolean smartToggle) {
            // no-op
        }

        @Override
        public void unselected(ITextViewer viewer) {
            // no-op
        }

        @Override
        public boolean validate(IDocument doc, int docOffset, org.eclipse.jface.text.DocumentEvent event) {
            return false;
        }
    }
}
