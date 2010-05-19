/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.sam;

import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.Cigar;
import net.sf.samtools.CigarElement;
import net.sf.samtools.util.StringUtil;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.utils.pileup.*;
import org.broadinstitute.sting.utils.StingException;
import org.broadinstitute.sting.utils.BaseUtils;


public class AlignmentUtils {

    private static class MismatchCount {
        public int numMismatches = 0;
        public long mismatchQualities = 0;
    }

    /** Returns number of mismatches in the alignment <code>r</code> to the reference sequence
     * <code>refSeq</code> assuming the alignment starts at (ZERO-based) position <code>refIndex</code> on the
     * specified reference sequence; in other words, <code>refIndex</code> is used in place of alignment's own
     * getAlignmentStart() coordinate and the latter is never used. However, the structure of the alignment <code>r</code>
     * (i.e. it's cigar string with all the insertions/deletions it may specify) is fully respected.
     *
     * THIS CODE ASSUMES THAT ALL BYTES COME FROM UPPERCASED CHARS.
     * 
     * @param r alignment
     * @param refSeq chunk of reference sequence that subsumes the alignment completely (if alignment runs out of 
     *                  the reference string, IndexOutOfBound exception will be thrown at runtime).
     * @param refIndex zero-based position, at which the alignment starts on the specified reference string. 
     * @return the number of mismatches
     */
    public static int numMismatches(SAMRecord r, byte[] refSeq, int refIndex) {
        return getMismatchCount(r, refSeq, refIndex).numMismatches;
    }

    public static int numMismatches(SAMRecord r, String refSeq, int refIndex ) {
        if ( r.getReadUnmappedFlag() ) return 1000000;
        return numMismatches(r, StringUtil.stringToBytes(refSeq), refIndex);
     }

    public static long mismatchingQualities(SAMRecord r, byte[] refSeq, int refIndex) {
        return getMismatchCount(r, refSeq, refIndex).mismatchQualities;
    }

    public static long mismatchingQualities(SAMRecord r, String refSeq, int refIndex ) {
        if ( r.getReadUnmappedFlag() ) return 1000000;
        return numMismatches(r, StringUtil.stringToBytes(refSeq), refIndex);
     }

    private static MismatchCount getMismatchCount(SAMRecord r, byte[] refSeq, int refIndex) {
        MismatchCount mc = new MismatchCount();

        int readIdx = 0;
        byte[] readSeq = r.getReadBases();
        Cigar c = r.getCigar();
        for (int i = 0 ; i < c.numCigarElements() ; i++) {
            CigarElement ce = c.getCigarElement(i);
            switch ( ce.getOperator() ) {
                case M:
                    for (int j = 0 ; j < ce.getLength() ; j++, refIndex++, readIdx++ ) {
                        if ( refIndex >= refSeq.length )
                            continue;
                        byte refChr = refSeq[refIndex];
                        byte readChr = readSeq[readIdx];
                        // Note: we need to count X/N's as mismatches because that's what SAM requires
                        //if ( BaseUtils.simpleBaseToBaseIndex(readChr) == -1 ||
                        //     BaseUtils.simpleBaseToBaseIndex(refChr)  == -1 )
                        //    continue; // do not count Ns/Xs/etc ?
                        if ( readChr != refChr ) {
                            mc.numMismatches++;
                            mc.mismatchQualities += r.getBaseQualities()[readIdx];
                        }
                    }
                    break;
                case I:
                case S:
                    readIdx += ce.getLength();
                    break;
                case D:
                case N:
                    refIndex += ce.getLength();
                    break;
                case H:
                case P:
                    break;
                default: throw new StingException("The " + ce.getOperator() + " cigar element is not currently supported");
            }

        }
        return mc;
    }

    /** Returns the number of mismatches in the pileup within the given reference context.
     *
     * @param pileup  the pileup with reads
     * @param ref     the reference context
     * @param ignoreTargetSite     if true, ignore mismatches at the target locus (i.e. the center of the window)
     * @return the number of mismatches
     */
    public static int mismatchesInRefWindow(ReadBackedPileup pileup, ReferenceContext ref, boolean ignoreTargetSite) {
        int mismatches = 0;
        for ( PileupElement p : pileup )
            mismatches += mismatchesInRefWindow(p, ref, ignoreTargetSite);
        return mismatches;
    }

    /** Returns the number of mismatches in the pileup element within the given reference context.
     *
     * @param p       the pileup element
     * @param ref     the reference context
     * @param ignoreTargetSite     if true, ignore mismatches at the target locus (i.e. the center of the window)
     * @return the number of mismatches
     */
    public static int mismatchesInRefWindow(PileupElement p, ReferenceContext ref, boolean ignoreTargetSite) {
        return mismatchesInRefWindow(p, ref, ignoreTargetSite, false);
    }

    /** Returns the number of mismatches in the pileup element within the given reference context.
     *
     * @param p       the pileup element
     * @param ref     the reference context
     * @param ignoreTargetSite     if true, ignore mismatches at the target locus (i.e. the center of the window)
     * @param qualitySumInsteadOfMismatchCount if true, return the quality score sum of the mismatches rather than the count
     * @return the number of mismatches
     */
    public static int mismatchesInRefWindow(PileupElement p, ReferenceContext ref, boolean ignoreTargetSite, boolean qualitySumInsteadOfMismatchCount) {
        int sum = 0;

        int windowStart = (int)ref.getWindow().getStart();
        int windowStop = (int)ref.getWindow().getStop();
        byte[] refBases = ref.getBases();
        byte[] readBases = p.getRead().getReadBases();
        byte[] readQualities = p.getRead().getBaseQualities();
        Cigar c = p.getRead().getCigar();

        int readIndex = 0;
        int currentPos = p.getRead().getAlignmentStart();
        int refIndex = Math.max(0, currentPos - windowStart);

        for (int i = 0 ; i < c.numCigarElements() ; i++) {
            CigarElement ce = c.getCigarElement(i);
            int cigarElementLength = ce.getLength();
            switch ( ce.getOperator() ) {
                case M:
                    for (int j = 0; j < cigarElementLength; j++, readIndex++, currentPos++) {
                        // are we past the ref window?
                        if ( currentPos > windowStop )
                            break;

                        // are we before the ref window?
                        if ( currentPos < windowStart )
                            continue;

                        byte refChr = refBases[refIndex++];

                        // do we need to skip the target site?
                        if ( ignoreTargetSite && ref.getLocus().getStart() == currentPos )
                            continue;

                        char readChr = (char)readBases[readIndex];
                        if ( Character.toUpperCase(readChr) != Character.toUpperCase(refChr) )                       
                            sum += (qualitySumInsteadOfMismatchCount) ? readQualities[readIndex] : 1;
                    }
                    break;
                case I:
                case S:
                    readIndex += cigarElementLength;
                    break;
                case D:
                case N:
                    currentPos += cigarElementLength;
                    if ( currentPos > windowStart )
                        refIndex += Math.min(cigarElementLength, currentPos - windowStart);
                    break;
                default:
                    // fail silently
                    return 0;
            }
        }

        return sum;
    }

    /** Returns number of alignment blocks (continuous stretches of aligned bases) in the specified alignment.
     * This method follows closely the SAMRecord::getAlignmentBlocks() implemented in samtools library, but
     * it only counts blocks without actually allocating and filling the list of blocks themselves. Hence, this method is
     * a much more efficient alternative to r.getAlignmentBlocks.size() in the situations when this number is all that is needed.
     * Formally, this method simply returns the number of M elements in the cigar. 
     * @param r alignment
     * @return number of continuous alignment blocks (i.e. 'M' elements of the cigar; all indel and clipping elements are ignored).
     */
    public static int getNumAlignmentBlocks(final SAMRecord r) {
    	int n = 0;
        final Cigar cigar = r.getCigar();
        if (cigar == null) return 0;
 
        for (final CigarElement e : cigar.getCigarElements()) {
        	if (e.getOperator() == CigarOperator.M ) n++;  
        }

    	return n;
    }

    public static String alignmentToString(final Cigar cigar,final  String seq, final String ref, final int posOnRef ) {
        return alignmentToString( cigar, seq, ref, posOnRef, 0 );
    }

    public static String cigarToString(Cigar cig) {
        return cig.toString();
    }

    public static String alignmentToString(final Cigar cigar,final  String seq, final String ref, final int posOnRef, final int posOnRead ) {
        int readPos = posOnRead;
        int refPos = posOnRef;
        
        StringBuilder refLine = new StringBuilder();
        StringBuilder readLine = new StringBuilder();

        for ( int i = 0 ; i < posOnRead ; i++ ) {
            refLine.append( ref.charAt( refPos - readPos + i ) );
            readLine.append( seq.charAt(i) ) ;
        }

        for ( int i = 0 ; i < cigar.numCigarElements() ; i++ ) {

            final CigarElement ce = cigar.getCigarElement(i);

            switch(ce.getOperator()) {
            case I:
                for ( int j = 0 ; j < ce.getLength(); j++ ) {
                    refLine.append('+');
                    readLine.append( seq.charAt( readPos++ ) );
                }
                break;
            case D:
                for ( int j = 0 ; j < ce.getLength(); j++ ) {
                    readLine.append('*');
                    refLine.append( ref.charAt( refPos++ ) );
                }
                break;
            case M:
                for ( int j = 0 ; j < ce.getLength(); j++ ) {
                    refLine.append(ref.charAt( refPos++ ) );
                    readLine.append( seq.charAt( readPos++ ) );
                }
                break;
            default: throw new StingException("Unsupported cigar operator: "+ce.getOperator() );
            }
        }
        refLine.append('\n');
        refLine.append(readLine);
        refLine.append('\n');
        return refLine.toString();
    }

    public static char[] alignmentToCharArray( final Cigar cigar, final char[] read, final char[] ref ) {

        final char[] alignment = new char[read.length];
        int refPos = 0;
        int alignPos = 0;

        for ( int iii = 0 ; iii < cigar.numCigarElements() ; iii++ ) {

            final CigarElement ce = cigar.getCigarElement(iii);

            switch( ce.getOperator() ) {
            case I:
            case S:
                for ( int jjj = 0 ; jjj < ce.getLength(); jjj++ ) {
                    alignment[alignPos++] = '+';
                }
                break;
            case D:
            case N:
                refPos++;
                break;
            case M:
                for ( int jjj = 0 ; jjj < ce.getLength(); jjj++ ) {
                    alignment[alignPos] = ref[refPos];
                    alignPos++;
                    refPos++;
                }
                break;
            default:
                throw new StingException( "Unsupported cigar operator: " + ce.getOperator() );
            }
        }
        return alignment;
    }

    /**
     * Due to (unfortunate) multiple ways to indicate that read is unmapped allowed by SAM format
     * specification, one may need this convenience shortcut. Checks both 'read unmapped' flag and
     * alignment reference index/start.
     * @param r record
     * @return true if read is unmapped
     */
    public static boolean isReadUnmapped(final SAMRecord r) {
        if ( r.getReadUnmappedFlag() ) return true;

        // our life would be so much easier if all sam files followed the specs. In reality,
        // sam files (including those generated by maq or bwa) miss headers alltogether. When
        // reading such a SAM file, reference name is set, but since there is no sequence dictionary,
        // null is always returned for referenceIndex. Let's be paranoid here, and make sure that
        // we do not call the read "unmapped" when it has only reference name set with ref. index missing
        // or vice versa.
        if ( ( r.getReferenceIndex() != null && r.getReferenceIndex() != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX
                || r.getReferenceName() != null && r.getReferenceName() != SAMRecord.NO_ALIGNMENT_REFERENCE_NAME )
          &&  r.getAlignmentStart() != SAMRecord.NO_ALIGNMENT_START ) return false  ;
        return true;
    }

    /** Returns the array of base qualitites in the order the bases were read on the machine (i.e. always starting from
     * cycle 1). In other words, if the read is unmapped or aligned in the forward direction, the read's own base
     * qualities are returned as stored in the SAM record; if the read is aligned in the reverse direction, the array
     * of read's base qualitites is inverted (in this case new array is allocated and returned).
      * @param read
     * @return
     */
    public static byte [] getQualsInCycleOrder(SAMRecord read) {
        if ( isReadUnmapped(read) || ! read.getReadNegativeStrandFlag() ) return read.getBaseQualities();

        return BaseUtils.reverse(read.getBaseQualities());
    }

    /** Takes the alignment of the read sequence <code>readSeq</code> to the reference sequence <code>refSeq</code>
     * starting at 0-based position <code>refIndex</code> on the <code>refSeq</code> and specified by its <code>cigar</code>.
     * The last argument <code>readIndex</code> specifies 0-based position on the read where the alignment described by the
     * <code>cigar</code> starts. Usually cigars specify alignments of the whole read to the ref, so that readIndex is normally 0.
     * Use non-zero readIndex only when the alignment cigar represents alignment of a part of the read. The refIndex in this case
     * should be the position where the alignment of that part of the read starts at. In other words, both refIndex and readIndex are
     * always the positions where the cigar starts on the ref and on the read, respectively.
     *
     * If the alignment has an indel, then this method attempts moving this indel left across a stretch of repetitive bases. For instance, if the original cigar
     * specifies that (any) one AT  is deleted from a repeat sequence TATATATA, the output cigar will always mark the leftmost AT
     * as deleted. If there is no indel in the original cigar, or the indel position is determined unambiguously (i.e. inserted/deleted sequence
     * is not repeated), the original cigar is returned.
     * @param cigar structure of the original alignment
     * @param refSeq reference sequence the read is aligned to
     * @param readSeq read sequence
     * @param refIndex 0-based alignment start position on ref
     * @param readIndex 0-based alignment start position on read
     * @return a cigar, in which indel is guaranteed to be placed at the leftmost possible position across a repeat (if any)
     */
    public static Cigar leftAlignIndel(Cigar cigar, final byte[] refSeq, final byte[] readSeq, final int refIndex, final int readIndex) {
        if ( cigar.numCigarElements() < 2 ) return cigar; // no indels, nothing to do

        final CigarElement ce1 = cigar.getCigarElement(0);
        final CigarElement ce2 = cigar.getCigarElement(1);

        // we currently can not handle clipped reads; alternatively, if the alignment starts from insertion, there
        // is no place on the read to move that insertion further left; so we are done:
        if ( ce1.getOperator() != CigarOperator.M ) return cigar;

        int difference = 0; // we can move indel 'difference' bases left
        final int indel_length = ce2.getLength();

        int period = 0; // period of the inserted/deleted sequence
        int indelIndexOnRef = refIndex+ce1.getLength() ; // position of the indel on the REF (first deleted base or first base after insertion)
        int indelIndexOnRead = readIndex+ce1.getLength(); // position of the indel on the READ (first insterted base, of first base after deletion)

        byte[] indelString = new byte[ce2.getLength()];  // inserted or deleted sequence

        if ( ce2.getOperator() == CigarOperator.D )
            System.arraycopy(refSeq, indelIndexOnRef, indelString, 0, ce2.getLength());
        else if ( ce2.getOperator() == CigarOperator.I )
            System.arraycopy(readSeq, indelIndexOnRead, indelString, 0, ce2.getLength());
        else
            // we can get here if there is soft clipping done at the beginning of the read
            // for now, we'll just punt the issue and not try to realign these
            return cigar;

        // now we have to check all WHOLE periods of the indel sequence:
        //  for instance, if
        //   REF:   AGCTATATATAGCC
        //   READ:   GCTAT***TAGCC
        // the deleted sequence ATA does have period of 2, but deletion obviously can not be
        // shifted left by 2 bases (length 3 does not contain whole number of periods of 2);
        // however if 4 bases are deleted:
        //   REF:   AGCTATATATAGCC
        //   READ:   GCTA****TAGCC
        // the length 4 is a multiple of the period of 2, and indeed deletion site can be moved left by 2 bases!
        //  Also, we will always have to check the length of the indel sequence itself (trivial period). If the smallest
        // period is 1 (which means that indel sequence is a homo-nucleotide sequence), we obviously do not have to check
        // any other periods.

        // NOTE: we treat both insertions and deletions in the same way below: we always check if the indel sequence
        // repeats itsels on the REF (never on the read!), even for insertions: if we see TA inserted and REF has, e.g., CATATA prior to the insertion
        // position, we will move insertion left, to the position right after CA. This way, while moving the indel across the repeat
        // on the ref, we can theoretically move it across a non-repeat on the read if the latter has a mismtach.

        while ( period < indel_length ) { // we will always get at least trivial period = indelStringLength

                period = BaseUtils.sequencePeriod(indelString, period+1);

                if ( indel_length % period != 0 ) continue; // if indel sequence length is not a multiple of the period, it's not gonna work

                int newIndex = indelIndexOnRef;

                while ( newIndex >= period ) { // let's see if there is a repeat, i.e. if we could also say that same bases at lower position are deleted

                    // lets check if bases [newIndex-period,newIndex) immediately preceding the indel on the ref
                    // are the same as the currently checked period of the inserted sequence:

                    boolean match = true;

                    for ( int testRefPos = newIndex - period, indelPos = 0 ; testRefPos < newIndex; testRefPos++, indelPos++) {
                        byte indelChr = indelString[indelPos];
                        if ( refSeq[testRefPos] != indelChr || !BaseUtils.isRegularBase((char)indelChr) ) {
                            match = false;
                            break;
                        }
                    }
                    if ( match ) {
                        newIndex -= period; // yes, they are the same, we can move indel farther left by at least period bases, go check if we can do more...
                    }
                    else {
                        break; // oops, no match, can not push indel farther left
                    }
                }

                final int newDifference = indelIndexOnRef - newIndex;
                if ( newDifference > difference ) difference = newDifference; // deletion should be moved 'difference' bases left

                if ( period == 1 ) break; // we do not have to check all periods of homonucleotide sequences, we already
                                          // got maximum possible shift after checking period=1 above.
        }

        //        if ( ce2.getLength() >= 2 )
        //            System.out.println("-----------------------------------\n  FROM:\n"+AlignmentUtils.alignmentToString(cigar,readSeq,refSeq,refIndex, (readIsConsensusSequence?refIndex:0)));


        if ( difference > 0 ) {

            // The following if() statement: this should've never happened, unless the alignment is really screwed up.
            // A real life example:
            //
            //   ref:    TTTTTTTTTTTTTTTTTT******TTTTTACTTATAGAAGAAAT...
            //  read:       GTCTTTTTTTTTTTTTTTTTTTTTTTACTTATAGAAGAAAT...
            //
            //  i.e. the alignment claims 6 T's to be inserted. The alignment is clearly malformed/non-conforming since we could
            // have just 3 T's inserted (so that the beginning of the read maps right onto the beginning of the
            // reference fragment shown): that would leave us with same 2 mismatches at the beginning of the read
            // (G and C) but lower gap penalty. Note that this has nothing to do with the alignment being "right" or "wrong"
            // with respect to where on the DNA the read actually came from. It is the assumptions of *how* the alignments are
            // built and represented that are broken here. While it is unclear how the alignment shown above could be generated
            // in the first place, we are not in the business of fixing incorrect alignments in this method; all we are
            // trying to do is to left-adjust correct ones. So if something like that happens, we refuse to change the cigar
            // and bail out.
            if ( ce1.getLength()-difference < 0 ) return cigar;

            Cigar newCigar = new Cigar();
            // do not add leading M cigar element if its length is zero (i.e. if we managed to left-shift the
            // insertion all the way to the read start):
            if ( ce1.getLength() - difference > 0 )
                newCigar.add(new CigarElement(ce1.getLength()-difference, CigarOperator.M));
            newCigar.add(ce2);  // add the indel, now it's left shifted since we decreased the number of preceding matching bases

            if ( cigar.numCigarElements() > 2 ) {
                // if we got something following the indel element:

                if ( cigar.getCigarElement(2).getOperator() == CigarOperator.M  ) {
                    // if indel was followed by matching bases (that's the most common situation),
                    // increase the length of the matching section after the indel by the amount of left shift
                    // (matching bases that were on the left are now *after* the indel; we have also checked at the beginning
                    // that the first cigar element was also M):
                    newCigar.add(new CigarElement(cigar.getCigarElement(2).getLength()+difference, CigarOperator.M));
                } else {
                    // if the element after the indel was not M, we have to add just the matching bases that were on the left
                    // and now appear after the indel after we performed the shift. Then add the original element that followed the indel.
                    newCigar.add(new CigarElement(difference, CigarOperator.M));
                    newCigar.add(new CigarElement(cigar.getCigarElement(2).getLength(),cigar.getCigarElement(2).getOperator()));
                }
                // now add remaining (unchanged) cigar elements, if any:
                for ( int i = 3 ; i < cigar.numCigarElements() ; i++ )  {
                    newCigar.add(new CigarElement(cigar.getCigarElement(i).getLength(),cigar.getCigarElement(i).getOperator()));
                }
            }

            //logger.debug("Realigning indel: " + AlignmentUtils.cigarToString(cigar) + " to " + AlignmentUtils.cigarToString(newCigar));
            cigar = newCigar;

        }
        return cigar;
    }
}
