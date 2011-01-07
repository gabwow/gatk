import org.broadinstitute.sting.commandline.ArgumentCollection
import org.broadinstitute.sting.queue.extensions.gatk._
import org.broadinstitute.sting.queue.library.ipf.ExpandIntervals
import org.broadinstitute.sting.queue.pipeline.PipelineArgumentCollection
import org.broadinstitute.sting.queue.QScript
import org.broadinstitute.sting.utils.text.XReadLines
import collection.JavaConversions._

class expanded_targets extends QScript {
  @ArgumentCollection var args : PipelineArgumentCollection = new PipelineArgumentCollection
  @Argument(shortName="bait",doc="The list of baits associated with the target list",required=false) var baitFile : File = _
  @Argument(shortName="thisTrigger",doc="The trigger track to use",required=false) var thisTrigger : File = new File("/humgen/gsa-hphome1/chartl/projects/exome/expanded/triggers/joined.omni.hiseq.vcf")

  def script = {

    val intervalExpands : List[ExpandIntervals] = (new Range(0,40,1)).toList.map( u => {
      new ExpandIntervals(args.projectIntervals,1+5*u,5,new File("./"+args.projectName+"_expanded_%d_%d.interval_list".format(1+5*u,6+5*u)),args.projectRef,"TSV","INTERVALS")
    })

     trait GATKArgs extends CommandLineGATK {
      this.reference_sequence = args.projectRef
      this.DBSNP = args.projectDBSNP
      this.jarFile = args.gatkJar
    }

    val userDir = "."

    addAll(intervalExpands)

    val cleanIntervals : ExpandIntervals = new ExpandIntervals(args.projectIntervals,1,210,new File(userDir+"/"+args.projectName+"_expanded_full.interval_list"),args.projectRef,"TSV","INTERVALS")

    add(cleanIntervals)

    val uncleanBams : List[File] = asScalaIterable(new XReadLines(args.projectBams)).toList.map(u => new File(u))
    val realign : List[RealignerTargetCreator] = uncleanBams.map(u => {
      var rtc : RealignerTargetCreator = new RealignerTargetCreator with GATKArgs
      rtc.out = swapExt(userDir,u,".bam",".clean.targets.interval_list")
      rtc.input_file :+= u.getAbsoluteFile
      rtc.intervals :+= cleanIntervals.outList
      rtc.memoryLimit = Some(4)
      rtc
    })
    val clean : List[IndelRealigner] = realign.map( u => {
      var cleaner : IndelRealigner = new IndelRealigner with GATKArgs
      cleaner.targetIntervals = u.out
      cleaner.input_file = u.input_file
      cleaner.memoryLimit = Some(4)
      cleaner.out = new File(userDir+"/"+swapExt(u.out,".bam",".expanded.targets.bam").getName)
      cleaner.intervals :+= cleanIntervals.outList
      cleaner
    })

    addAll(realign)
    addAll(clean)

    val callFiles: List[File] = intervalExpands.map(u => makeCalls(u.outList,clean.map(h => h.out)))

  }

  def makeCalls(iList: File,  bams: List[File]): File = {

    trait GATKArgs extends CommandLineGATK {
      this.reference_sequence = args.projectRef
      this.DBSNP = args.projectDBSNP
      this.jarFile = args.gatkJar
      this.intervals :+= iList
    }

    var call : UnifiedGenotyper = new UnifiedGenotyper with GATKArgs
    call.input_file = bams
    call.out = swapExt(iList,".interval_list",".raw.vcf")
    call.trig_emit_conf = Some(0.0)
    call.rodBind :+= new RodBind("trigger","vcf",thisTrigger)
    call.scatterCount = 10
    call.memoryLimit = Some(6)
    var filter : VariantFiltration = new VariantFiltration with GATKArgs
    filter.rodBind :+= new RodBind("variant","vcf",call.out)
    filter.filterExpression :+= "\"QD<5.0\""
    filter.filterName :+= "LowQualByDepth"
    filter.filterExpression :+= "\"SB>-0.10\""
    filter.filterName :+= "HighStrandBias"
    filter.out = swapExt(iList,".interval_list",".filtered.vcf")
    var callHiseq : UnifiedGenotyper = new UnifiedGenotyper with GATKArgs
    callHiseq.input_file = List(new File("/humgen/1kg/analysis/bamsForDataProcessingPapers/NA12878.HiSeq.WGS.bwa.cleaned.recal.bam"))
    callHiseq.rodBind :+= new RodBind("trigger","vcf",filter.out)
    callHiseq.out = swapExt(iList,".interval_list",".hiSeq.genotypes.vcf")
    callHiseq.trig_emit_conf = Some(0.0)
    callHiseq.scatterCount = 5

    add(call,filter,callHiseq)

    var eval : VariantEval = new VariantEval with GATKArgs
    eval.rodBind :+= new RodBind("evalInterval","vcf",filter.out)
    eval.rodBind :+= new RodBind("compHiSeq","vcf",new File("/humgen/gsa-hpprojects/GATK/data/Comparisons/Unvalidated/NA12878/NA12878.hg19.HiSeq.WGS.cleaned.ug.snpfiltered.indelfiltered.optimized.cut.vcf"))
    eval.rodBind :+= new RodBind("compHiSeq_atSites","vcf",callHiseq.out)
    eval.rodBind :+= new RodBind("compOMNI","vcf",new File("/humgen/gsa-hpprojects/GATK/data/Comparisons/Validated/Omni2.5_chip/Omni_2.5_764_samples.b37.deduped.annot.vcf"))
    eval.out = swapExt(iList,".interval_list",".eval")
    eval.reportType = Option(org.broadinstitute.sting.utils.report.VE2ReportFactory.VE2TemplateType.CSV)

    add(eval)
    eval.out

  }
}