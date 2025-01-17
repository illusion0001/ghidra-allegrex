package ghidra.app.util.bin.format.elf.relocation

import ghidra.app.util.bin.format.elf.ElfHeader
import ghidra.app.util.bin.format.elf.ElfLoadHelper
import ghidra.app.util.bin.format.elf.ElfRelocation
import ghidra.app.util.bin.format.elf.ElfRelocationTable
import ghidra.app.util.bin.format.elf.ElfSymbol
import ghidra.app.util.bin.format.elf.PspElfConstants
import ghidra.program.model.address.Address

@SuppressWarnings("unused")
open class AllegrexElfRelocationHandler : ElfRelocationHandler() {
  override fun canRelocate(elf: ElfHeader): Boolean {
    return elf.e_machine() == PspElfConstants.EM_MIPS_PSP_HACK
  }

  override fun createRelocationContext(
    loadHelper: ElfLoadHelper, relocTable: ElfRelocationTable, symbolMap: Map<ElfSymbol, Address>
  ): AllegrexElfRelocationContext {
    return AllegrexElfRelocationContext(this, loadHelper, relocTable, symbolMap)
  }

  override fun relocate(elfContext: ElfRelocationContext, relocation: ElfRelocation, relocationAddress: Address) {
    if (elfContext.elfHeader.e_machine() != PspElfConstants.EM_MIPS_PSP_HACK) {
      return
    }

    val context = elfContext as AllegrexElfRelocationContext
    val program = context.getProgram()
    val memory = program.memory
    val programHeaders = context.elfHeader.programHeaders
    val log = context.log

    if (elfContext.relocationTable.tableSectionHeader?.type != PspElfConstants.SHT_PSP_REL) {
      ElfRelocationHandler.markAsUnhandled(
        program, relocationAddress, relocation.type.toLong(),
        relocation.symbolIndex.toLong(), elfContext.getSymbol(relocation.symbolIndex).nameAsString,
        log
      )
      return
    }

    val info = relocation.relocationInfo.toInt()
    val type = info and 0xFF
    val relativeIndex = info shr 8 and 0xFF
    val relocateToIndex = info shr 16 and 0xFF

    val relativeSect = programHeaders[relativeIndex].virtualAddress.toInt()
    val addr = relocationAddress.add(relativeSect.toLong())
    val relocateToSect = program.imageBase.add(programHeaders[relocateToIndex].virtualAddress).offset.toInt()

    val currentValue = memory.getInt(addr)
    var newValue = 0
    var relocAccepted = false

    when (type) {
      AllegrexElfRelocationConstants.R_MIPS_NONE -> {
        relocAccepted = true
      }
      AllegrexElfRelocationConstants.R_MIPS_16 -> {
        newValue = relocate(currentValue, 0xFFFF, relocateToSect)
        relocAccepted = true
      }
      AllegrexElfRelocationConstants.R_MIPS_32 -> {
        newValue = currentValue + relocateToSect
        relocAccepted = true
      }
      AllegrexElfRelocationConstants.R_MIPS_26 -> {
        newValue = relocate(currentValue, 0x3FFFFFF, relocateToSect shr 2)
        relocAccepted = true
      }
      AllegrexElfRelocationConstants.R_MIPS_HI16 -> {
        context.deferMipsHi16Relocation(
          AllegrexDeferredRelocation(type, relocateToSect, addr, currentValue)
        )
        relocAccepted = true
      }
      AllegrexElfRelocationConstants.R_MIPS_LO16 -> {
        newValue = relocate(currentValue, 0xFFFF, relocateToSect)
        context.completeMipsHi16Relocations((currentValue and 0xFFFF).toShort())
        relocAccepted = true
      }
    }

    if (newValue != 0) {
      memory.setInt(addr, newValue)
    }

    if (relocAccepted == false) {
      ElfRelocationHandler.markAsUnhandled(program, relocationAddress, type.toLong(), 0, "", log)
    }
  }

  private fun relocate(data: Int, mask: Int, relocateTo: Int): Int {
    return (data and mask.inv()) or ((data and mask) + relocateTo and mask)
  }

  /** Provides extended relocation context with the ability to retain deferred relocation lists.  */
  class AllegrexElfRelocationContext constructor(
    handler: AllegrexElfRelocationHandler, loadHelper: ElfLoadHelper,
    relocationTable: ElfRelocationTable, symbolMap: Map<ElfSymbol, Address>
  ) : ElfRelocationContext(handler, loadHelper, relocationTable, symbolMap) {
    private val deferredMipsHi16Relocations = ArrayList<AllegrexDeferredRelocation>()

    fun deferMipsHi16Relocation(reloc: AllegrexDeferredRelocation) {
      deferredMipsHi16Relocations.add(reloc)
    }

    fun completeMipsHi16Relocations(lo: Short) {
      for (reloc in deferredMipsHi16Relocations) {
        var newAddr = reloc.oldValue shl 16
        newAddr += lo.toInt()
        newAddr += reloc.relocToSect
        val newLo = (newAddr and 0xFFFF).toShort()
        val newHi = (newAddr - newLo) shr 16
        val newData = (reloc.oldValue and 0xFFFF0000.toInt()) or newHi
        program.memory.setInt(reloc.relocAddr, newData)
      }
      deferredMipsHi16Relocations.clear()
    }

    override fun dispose() {
      for (reloc in deferredMipsHi16Relocations) {
        reloc.markUnprocessed(this, "LO16 Relocation")
      }

      super.dispose()
    }
  }

  class AllegrexDeferredRelocation constructor(
    val relocType: Int,
    val relocToSect: Int,
    val relocAddr: Address,
    val oldValue: Int
  ) {
    internal fun markUnprocessed(
      mipsRelocationContext: AllegrexElfRelocationContext,
      missingDependencyName: String
    ) {
      ElfRelocationHandler.markAsError(
        mipsRelocationContext.getProgram(), relocAddr, Integer.toString(relocType),
        "", "Relocation missing required $missingDependencyName",
        mipsRelocationContext.log
      )
    }
  }
}
