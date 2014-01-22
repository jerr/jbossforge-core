/**
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.jboss.forge.addon.shell.command;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.jboss.aesh.console.settings.SettingsBuilder;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.resource.Resource;
import org.jboss.forge.addon.resource.ResourceFactory;
import org.jboss.forge.addon.shell.Shell;
import org.jboss.forge.addon.shell.ShellFactory;
import org.jboss.forge.addon.shell.ui.AbstractShellCommand;
import org.jboss.forge.addon.shell.ui.ShellContext;
import org.jboss.forge.addon.shell.util.PathspecParser;
import org.jboss.forge.addon.ui.command.AbstractCommandExecutionListener;
import org.jboss.forge.addon.ui.command.CommandExecutionListener;
import org.jboss.forge.addon.ui.command.UICommand;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.hints.InputType;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.input.UIInputMany;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.furnace.exception.ContainerException;
import org.jboss.forge.furnace.spi.ListenerRegistration;
import org.jboss.forge.furnace.util.Assert;
import org.jboss.forge.furnace.util.OperatingSystemUtils;

/**
 * Implementation of the "run script" command
 * 
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
public class RunCommand extends AbstractShellCommand
{
   @Inject
   ResourceFactory resourceFactory;

   @Inject
   @WithAttributes(label = "Timeout (seconds)", defaultValue = "15", required = false,
            description = "Set the timeout after which this script should abort if execution has not completed.")
   private UIInput<Integer> timeout;

   @Inject
   @WithAttributes(label = "Arguments", type = InputType.FILE_PICKER, required = true)
   private UIInputMany<String> arguments;

   @Inject
   private ShellFactory shellFactory;

   @Override
   public UICommandMetadata getMetadata(UIContext context)
   {
      return Metadata.from(super.getMetadata(context), getClass()).name("run")
               .description("Execute/run a forge script file.");
   }

   @Override
   public void initializeUI(UIBuilder builder) throws Exception
   {
      builder.add(arguments);
   }

   @Override
   public Result execute(UIExecutionContext context) throws Exception
   {
      Resource<?> currentResource = (Resource<?>) context.getUIContext().getInitialSelection().get();
      Shell shell = (Shell) context.getUIContext().getProvider();
      for (String path : arguments.getValue())
      {
         List<Resource<?>> resources = new PathspecParser(resourceFactory, currentResource, path).resolve();
         for (Resource<?> resource : resources)
         {
            if (resource.exists())
            {
               PipedOutputStream stdin = new PipedOutputStream();
               BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin));

               Shell scriptShell = shellFactory.createShell((File) resource.getParent().getUnderlyingResourceObject(),
                        new SettingsBuilder().inputStream(new PipedInputStream(stdin))
                                 .outputStream(shell.getConsole().getShell().out())
                                 .outputStreamError(shell.getConsole().getShell().err()).create());

               BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getResourceInputStream()));

               try
               {
                  long startTime = System.currentTimeMillis();
                  while (reader.ready())
                  {
                     try
                     {
                        execute(scriptShell, writer, reader.readLine(), timeout.getValue(), TimeUnit.SECONDS, startTime);
                     }
                     catch (TimeoutException e)
                     {
                        return Results.fail(path + ": timed out.");
                     }
                  }
               }
               finally
               {
                  reader.close();
                  scriptShell.close();
               }
            }
            else
            {
               return Results.fail(path + ": not found.");
            }
         }
      }

      return Results.success();
   }

   public Result execute(Shell shell, BufferedWriter stdin, String line, int quantity, TimeUnit unit, long startTime)
            throws TimeoutException
   {
      Assert.notNull(line, "Line to execute cannot be null.");

      Result result;
      if (!line.trim().endsWith(OperatingSystemUtils.getLineSeparator()))
         line = line + OperatingSystemUtils.getLineSeparator();
      ScriptCommandListener listener = new ScriptCommandListener();
      ListenerRegistration<CommandExecutionListener> listenerRegistration = shell.addCommandExecutionListener(listener);
      try
      {
         stdin.write(line);
         stdin.flush();
         while (!listener.isExecuted())
         {
            if (System.currentTimeMillis() > (startTime + TimeUnit.MILLISECONDS.convert(quantity, unit)))
            {
               throw new TimeoutException("Timeout expired waiting for command [" + line + "] to execute.");
            }

            try
            {
               Thread.sleep(10);
            }
            catch (InterruptedException e)
            {
               throw new ContainerException("Command [" + line + "] did not respond.", e);
            }
         }
         result = listener.getResult();
      }
      catch (IOException e)
      {
         throw new RuntimeException("Failed to execute command.", e);
      }
      finally
      {
         listenerRegistration.removeListener();
      }
      return result;
   }

   public class ScriptCommandListener extends AbstractCommandExecutionListener
   {
      Result result;

      @Override
      public void preCommandExecuted(UICommand command, UIExecutionContext context)
      {
      }

      @Override
      public void postCommandExecuted(UICommand command, UIExecutionContext context, Result result)
      {
         synchronized (this)
         {
            this.result = result;
         }
      }

      public boolean isExecuted()
      {
         synchronized (this)
         {
            return result != null;
         }
      }

      public Result getResult()
      {
         synchronized (this)
         {
            return result;
         }
      }

      public void reset()
      {
         synchronized (this)
         {
            result = null;
         }
      }
   }

   @Override
   public boolean isEnabled(ShellContext context)
   {
      return super.isEnabled(context) && context.getInitialSelection().get() instanceof DirectoryResource;
   }
}