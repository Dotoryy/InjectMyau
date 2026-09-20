package myau.command.commands;

import myau.command.Command;
import myau.inject.HookRegistry;
import myau.inject.HookTransformer;
import myau.inject.NativeBridge;
import myau.util.ChatUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class HooksCommand extends Command {
    public HooksCommand() {
        super(new ArrayList<>(Collections.singletonList("hooks")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        HookTransformer transformer = NativeBridge.transformer();
        if (transformer == null) {
            ChatUtil.sendFormatted("&cInject transformer is not active (Forge/mixin build?)");
            return;
        }
        List<HookRegistry.Hook> all = HookRegistry.allHooks();
        Set<String> applied = transformer.appliedKeys();
        Set<String> patchedOwners = transformer.patchedOwners();

        List<String> missingRequired = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();
        for (HookRegistry.Hook hook : all) {
            if (applied.contains(HookRegistry.keyOf(hook))) {
                continue;
            }
            String line = HookRegistry.keyOf(hook)
                    + (patchedOwners.contains(hook.owner) ? "" : "  [class never transformed]");
            if (hook.required) {
                missingRequired.add(line);
            } else {
                missingOptional.add(line);
            }
        }

        ChatUtil.sendFormatted(String.format(
                "&7hooks: &a%d applied&7 / &f%d total&7, &c%d required missing&7, &e%d optional missing",
                applied.size(), all.size(), missingRequired.size(), missingOptional.size()));

        for (String line : missingRequired) {
            ChatUtil.sendFormatted("&cMISSING &7" + line);
        }
        boolean verbose = args.size() >= 2 && args.get(1).equalsIgnoreCase("all");
        if (verbose) {
            for (String line : missingOptional) {
                ChatUtil.sendFormatted("&eopt &7" + line);
            }
        } else if (!missingOptional.isEmpty()) {
            ChatUtil.sendFormatted("&7(" + missingOptional.size()
                    + " optional missing, use &f.hooks all&7 to list)");
        }

        List<String> failures = transformer.failures();
        if (failures.isEmpty()) {
            ChatUtil.sendFormatted("&7no transform failures recorded");
            return;
        }
        ChatUtil.sendFormatted("&7transform failures (&c" + failures.size() + "&7):");
        int shown = 0;
        for (String failure : failures) {
            if (!verbose && shown >= 15) {
                ChatUtil.sendFormatted("&7... " + (failures.size() - shown)
                        + " more, use &f.hooks all");
                break;
            }
            ChatUtil.sendFormatted("&c- &7" + failure);
            shown++;
        }
    }
}
